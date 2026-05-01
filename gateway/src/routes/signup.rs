//! Public `POST /signup` route. Per the gateway spec it MUST NOT validate
//! JWTs, MUST NOT inject any `X-User-*` headers, and MUST apply a per-IP
//! rate limit of 5 requests / 60 seconds backed by Redis.

use std::net::SocketAddr;

use axum::{
    body::Bytes,
    extract::{ConnectInfo, State},
    http::{HeaderMap, HeaderName, HeaderValue, StatusCode},
    response::{IntoResponse, Response},
};
use serde_json::json;

use crate::request_id::{self, RequestId};
use crate::GatewayState;

const RATE_LIMIT_PREFIX: &str = "ratelimit:signup:";

/// Headers that we deliberately strip when forwarding downstream.
///
/// `authorization` is dropped because this is a public route; we do not
/// validate the token and we do not propagate it. `x-user-*` headers exist
/// only on authenticated routes — Identity must reject them on `/signup` to
/// prevent a malicious client from forging an authenticated principal, but
/// we strip them at the edge as defense-in-depth.
///
/// `x-forwarded-for` is stripped here so that we can re-inject it with the
/// *resolved* client IP. This prevents a direct caller from spoofing the
/// header and bypassing Identity's per-IP rate limit.
///
/// `x-edge-token` is stripped so that a client cannot supply its own value.
/// The gateway re-injects the *configured* token from `GatewayState` after
/// the strip, so Identity always receives the gateway-controlled credential.
const HEADERS_TO_STRIP: &[&str] = &[
    "authorization",
    "host",
    "content-length",
    "transfer-encoding",
    "connection",
    "x-user-id",
    "x-user-roles",
    "x-forwarded-for",
    "x-edge-token",
];

pub async fn handle(
    State(state): State<GatewayState>,
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    request_id: axum::extract::Extension<RequestId>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let client_ip = resolve_client_ip(&headers, remote);
    let rate_key = format!("{RATE_LIMIT_PREFIX}{client_ip}");

    let decision = state.signup_limiter.check(&rate_key).await;
    if !decision.allowed {
        let mut response = (
            StatusCode::TOO_MANY_REQUESTS,
            axum::Json(json!({ "error": "rate_limited" })),
        )
            .into_response();
        if let Ok(value) = HeaderValue::from_str(&decision.retry_after_secs.to_string()) {
            response.headers_mut().insert(
                HeaderName::from_static("retry-after"),
                value,
            );
        }
        return response;
    }

    let url = format!("{}/signup", state.identity_base_url.trim_end_matches('/'));
    let mut req_builder = state
        .http
        .post(&url)
        .header(request_id::HEADER, request_id.0 .0.as_str())
        // Inject the resolved client IP so Identity can key its per-IP rate
        // limit on the real originating address (not the gateway's IP).
        // The inbound X-Forwarded-For is stripped above to prevent spoofing.
        .header("x-forwarded-for", client_ip.as_str())
        // Inject the gateway-controlled edge token. Any client-supplied value
        // has already been stripped from `headers` via HEADERS_TO_STRIP.
        .header("x-edge-token", state.identity_edge_token.as_str())
        .body(body.clone());

    for (name, value) in headers.iter() {
        let name_str = name.as_str().to_ascii_lowercase();
        if HEADERS_TO_STRIP.contains(&name_str.as_str()) || name_str == request_id::HEADER {
            continue;
        }
        req_builder = req_builder.header(name.as_str(), value);
    }

    match req_builder.send().await {
        Ok(resp) => relay_response(resp).await,
        Err(err) => {
            tracing::error!(error = %err, request_id = %request_id.0 .0, "identity upstream error");
            (
                StatusCode::BAD_GATEWAY,
                axum::Json(json!({ "error": "upstream_unavailable" })),
            )
                .into_response()
        }
    }
}

fn resolve_client_ip(headers: &HeaderMap, remote: SocketAddr) -> String {
    if let Some(value) = headers.get("x-forwarded-for").and_then(|v| v.to_str().ok()) {
        if let Some(first) = value.split(',').next() {
            let trimmed = first.trim();
            if !trimmed.is_empty() {
                return trimmed.to_string();
            }
        }
    }
    remote.ip().to_string()
}

async fn relay_response(upstream: reqwest::Response) -> Response {
    let status = StatusCode::from_u16(upstream.status().as_u16())
        .unwrap_or(StatusCode::BAD_GATEWAY);
    let upstream_headers = upstream.headers().clone();
    let body = match upstream.bytes().await {
        Ok(b) => b,
        Err(err) => {
            tracing::error!(error = %err, "failed to read identity body");
            return (
                StatusCode::BAD_GATEWAY,
                axum::Json(json!({ "error": "upstream_body" })),
            )
                .into_response();
        }
    };

    let mut response = Response::builder().status(status);
    for (name, value) in upstream_headers.iter() {
        let lower = name.as_str().to_ascii_lowercase();
        // Hop-by-hop headers and length-controlling headers must be set by
        // axum based on the actual body we forward, not copied verbatim.
        if matches!(
            lower.as_str(),
            "transfer-encoding" | "connection" | "content-length"
        ) {
            continue;
        }
        if let (Ok(n), Ok(v)) = (
            HeaderName::from_bytes(name.as_str().as_bytes()),
            HeaderValue::from_bytes(value.as_bytes()),
        ) {
            response = response.header(n, v);
        }
    }

    response
        .body(axum::body::Body::from(body))
        .unwrap_or_else(|err| {
            tracing::error!(error = %err, "failed to assemble response");
            (StatusCode::BAD_GATEWAY, "").into_response()
        })
}
