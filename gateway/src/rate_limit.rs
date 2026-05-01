//! Rate limiter abstraction.
//!
//! The gateway spec mandates that rate-limit state be stored in Redis so that
//! multiple gateway instances share counters. We hide that behind a trait so
//! integration tests can inject an in-memory implementation deterministically.
//!
//! The Redis implementation uses the canonical fixed-window counter pattern:
//! `INCR key`; if the new value is `1`, attach `EXPIRE key window`.

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use async_trait::async_trait;
use redis::{aio::ConnectionManager, AsyncCommands};

/// Decision returned by a rate limiter.
#[derive(Debug, Clone, Copy)]
pub struct Decision {
    pub allowed: bool,
    /// Seconds until the window resets. Surfaced as `Retry-After` on `429`.
    pub retry_after_secs: u64,
}

/// Per-key fixed-window rate limiter.
#[async_trait]
pub trait RateLimiter: Send + Sync + 'static {
    /// Record one hit against `key` and return whether it is allowed.
    async fn check(&self, key: &str) -> Decision;
}

// ---------------------------------------------------------------------------
// In-memory limiter (used by tests; never wired into the production binary).
// ---------------------------------------------------------------------------

/// Process-local fixed-window limiter. Suitable for tests and single-process
/// development only — production deployments MUST use the Redis variant so
/// counters are shared across replicas.
pub struct InMemoryLimiter {
    limit: u32,
    window: Duration,
    state: Mutex<HashMap<String, WindowState>>,
}

struct WindowState {
    count: u32,
    started_at: Instant,
}

impl InMemoryLimiter {
    pub fn new(limit: u32, window: Duration) -> Self {
        Self {
            limit,
            window,
            state: Mutex::new(HashMap::new()),
        }
    }
}

#[async_trait]
impl RateLimiter for InMemoryLimiter {
    async fn check(&self, key: &str) -> Decision {
        let now = Instant::now();
        let mut guard = self.state.lock().expect("rate-limit state poisoned");
        let entry = guard
            .entry(key.to_string())
            .or_insert_with(|| WindowState {
                count: 0,
                started_at: now,
            });

        if now.duration_since(entry.started_at) >= self.window {
            entry.count = 0;
            entry.started_at = now;
        }

        entry.count += 1;
        let elapsed = now.duration_since(entry.started_at);
        let retry_after_secs = self
            .window
            .saturating_sub(elapsed)
            .as_secs()
            .max(1);

        Decision {
            allowed: entry.count <= self.limit,
            retry_after_secs,
        }
    }
}

// ---------------------------------------------------------------------------
// Redis-backed limiter (production).
// ---------------------------------------------------------------------------

pub struct RedisLimiter {
    conn: ConnectionManager,
    limit: u32,
    window_secs: u64,
}

impl RedisLimiter {
    pub async fn connect(redis_url: &str, limit: u32, window_secs: u64) -> anyhow::Result<Self> {
        let client = redis::Client::open(redis_url)?;
        let conn = ConnectionManager::new(client).await?;
        Ok(Self {
            conn,
            limit,
            window_secs,
        })
    }
}

#[async_trait]
impl RateLimiter for RedisLimiter {
    async fn check(&self, key: &str) -> Decision {
        let mut conn = self.conn.clone();
        // We accept the (rare) race between INCR and EXPIRE: at worst, a key
        // never receives its TTL and lives forever. In practice the window is
        // 60 s and the keyspace is bounded by source IP — acceptable.
        let count: i64 = match conn.incr::<_, _, i64>(key, 1).await {
            Ok(n) => n,
            Err(err) => {
                tracing::warn!(error = %err, key = %key, "rate-limit redis INCR failed; failing open");
                return Decision {
                    allowed: true,
                    retry_after_secs: self.window_secs,
                };
            }
        };
        if count == 1 {
            let _: Result<(), _> = conn.expire::<_, ()>(key, self.window_secs as i64).await;
        }
        let ttl: i64 = conn.ttl::<_, i64>(key).await.unwrap_or(self.window_secs as i64);
        let retry_after_secs = ttl.max(1) as u64;

        Decision {
            allowed: count as u32 <= self.limit,
            retry_after_secs,
        }
    }
}
