//! Fast-Bet API gateway.
//!
//! Phase 1 responsibilities (per `docs/architecture.md` and the gateway spec):
//! authentication terminus, authorization, rate limiting, and routing. **No**
//! business logic. This crate exposes a small public API (`build_router`,
//! `GatewayState`) so that integration tests can mount the same router with
//! test doubles for the rate limiter and the identity backend.

pub mod config;
pub mod rate_limit;
pub mod request_id;
pub mod routes;

use std::sync::Arc;

use axum::Router;
use reqwest::Client;

use crate::rate_limit::RateLimiter;

/// Shared application state accessible from every handler.
#[derive(Clone)]
pub struct GatewayState {
    /// HTTP client used to forward requests downstream.
    pub http: Client,
    /// Base URL of the Identity service (e.g. `http://identity:8080`).
    pub identity_base_url: String,
    /// Per-IP rate limiter for the public `POST /signup` route
    /// (5 requests / 60 seconds).
    pub signup_limiter: Arc<dyn RateLimiter>,
    /// Shared edge token injected as `X-Edge-Token` on every upstream
    /// request to Identity. Clients cannot supply this header — the
    /// gateway strips any inbound value and replaces it with this secret.
    pub identity_edge_token: String,
}

impl GatewayState {
    pub fn new(
        http: Client,
        identity_base_url: impl Into<String>,
        signup_limiter: Arc<dyn RateLimiter>,
        identity_edge_token: impl Into<String>,
    ) -> Self {
        Self {
            http,
            identity_base_url: identity_base_url.into(),
            signup_limiter,
            identity_edge_token: identity_edge_token.into(),
        }
    }
}

/// Build the public gateway router. Tests construct this with their own state.
pub fn build_router(state: GatewayState) -> Router {
    Router::new()
        .route("/signup", axum::routing::post(routes::signup::handle))
        .layer(axum::middleware::from_fn(request_id::middleware))
        .with_state(state)
}
