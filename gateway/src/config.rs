//! Environment-driven configuration for the production binary.
//!
//! Tests construct `GatewayState` directly and bypass this module.

use std::env;

#[derive(Debug, Clone)]
pub struct Config {
    pub bind_addr: String,
    pub identity_base_url: String,
    pub redis_url: String,
    pub signup_rate_limit: u32,
    pub signup_rate_window_secs: u64,
    /// Shared secret injected as `X-Edge-Token` on every upstream request to
    /// Identity. Must be a non-blank value; startup fails otherwise.
    pub identity_edge_token: String,
}

impl Config {
    pub fn from_env() -> anyhow::Result<Self> {
        let identity_edge_token = env::var("IDENTITY_EDGE_TOKEN")
            .map_err(|_| anyhow::anyhow!("IDENTITY_EDGE_TOKEN is not set"))
            .and_then(|v| {
                if v.trim().is_empty() {
                    Err(anyhow::anyhow!("IDENTITY_EDGE_TOKEN must not be blank"))
                } else {
                    Ok(v)
                }
            })?;

        Ok(Self {
            bind_addr: env::var("GATEWAY_BIND_ADDR").unwrap_or_else(|_| "0.0.0.0:8080".into()),
            identity_base_url: env::var("IDENTITY_BASE_URL")
                .unwrap_or_else(|_| "http://identity:8080".into()),
            redis_url: env::var("REDIS_URL").unwrap_or_else(|_| "redis://redis:6379".into()),
            signup_rate_limit: env::var("SIGNUP_RATE_LIMIT")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(5),
            signup_rate_window_secs: env::var("SIGNUP_RATE_WINDOW_SECS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(60),
            identity_edge_token,
        })
    }
}
