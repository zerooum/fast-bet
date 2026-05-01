//! `X-Request-Id` propagation middleware.
//!
//! Reads the inbound `X-Request-Id` header (generating a UUIDv4 if absent),
//! stashes it in request extensions for handlers, and echoes it back on the
//! response so the client and downstream services can correlate logs.

use axum::{
    extract::Request,
    http::{HeaderName, HeaderValue},
    middleware::Next,
    response::Response,
};
use uuid::Uuid;

pub const HEADER: &str = "x-request-id";

/// Wrapper newtype so handlers can pull the request id out of extensions
/// without ambiguity with other `String` extensions.
#[derive(Debug, Clone)]
pub struct RequestId(pub String);

pub async fn middleware(mut req: Request, next: Next) -> Response {
    let header_name = HeaderName::from_static(HEADER);
    let request_id = req
        .headers()
        .get(&header_name)
        .and_then(|v| v.to_str().ok())
        .map(str::to_owned)
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| Uuid::new_v4().to_string());

    req.extensions_mut().insert(RequestId(request_id.clone()));

    let mut response = next.run(req).await;
    if let Ok(value) = HeaderValue::from_str(&request_id) {
        response.headers_mut().insert(header_name, value);
    }
    response
}
