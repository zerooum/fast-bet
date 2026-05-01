//! Integration test for the gateway's public `POST /signup` route.
//!
//! Covers the four behaviours required by the proposal (`tasks.md` §8.4):
//!   - Anonymous signup is forwarded to Identity unchanged.
//!   - An incoming `Authorization` header is *not* validated and *is*
//!     stripped before forwarding.
//!   - The 6th request from the same source IP within 60 s is rejected
//!     with `429` and a `Retry-After` header, without touching Identity.
//!   - The gateway-generated `X-Request-Id` is propagated downstream.

use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::Mutex;
use std::time::Duration;

use axum::{
    extract::State,
    http::{HeaderMap, StatusCode},
    routing::post,
    Json, Router,
};
use fastbet_gateway::{rate_limit::InMemoryLimiter, GatewayState};
use serde_json::{json, Value};

#[derive(Default, Clone)]
struct StubIdentity {
    received_authorization: Arc<Mutex<Vec<Option<String>>>>,
    received_request_ids: Arc<Mutex<Vec<Option<String>>>>,
    received_user_id: Arc<Mutex<Vec<Option<String>>>>,
    received_edge_token: Arc<Mutex<Vec<Option<String>>>>,
    hits: Arc<Mutex<u32>>,
    /// Decide the response: first call -> 201, subsequent same email -> 409.
    seen_emails: Arc<Mutex<Vec<String>>>,
}

async fn identity_handler(
    State(stub): State<StubIdentity>,
    headers: HeaderMap,
    Json(body): Json<Value>,
) -> (StatusCode, HeaderMap, Json<Value>) {
    *stub.hits.lock().unwrap() += 1;
    stub.received_authorization.lock().unwrap().push(
        headers
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned),
    );
    stub.received_request_ids.lock().unwrap().push(
        headers
            .get("x-request-id")
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned),
    );
    stub.received_user_id.lock().unwrap().push(
        headers
            .get("x-user-id")
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned),
    );
    stub.received_edge_token.lock().unwrap().push(
        headers
            .get("x-edge-token")
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned),
    );

    let email = body
        .get("email")
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string();

    let mut seen = stub.seen_emails.lock().unwrap();
    let mut response_headers = HeaderMap::new();
    response_headers.insert("content-type", "application/json".parse().unwrap());
    if seen.contains(&email) {
        (
            StatusCode::CONFLICT,
            response_headers,
            Json(json!({ "error": "email_taken" })),
        )
    } else {
        seen.push(email);
        response_headers.insert(
            "location",
            "/users/00000000-0000-0000-0000-000000000001".parse().unwrap(),
        );
        (
            StatusCode::CREATED,
            response_headers,
            Json(json!({
                "id": "00000000-0000-0000-0000-000000000001",
                "email": "ada@example.com",
                "displayName": "ada",
                "roles": ["USER"],
                "createdAt": "2026-04-28T12:34:56Z"
            })),
        )
    }
}

async fn spawn_stub_identity() -> (String, StubIdentity) {
    let stub = StubIdentity::default();
    let app = Router::new()
        .route("/signup", post(identity_handler))
        .with_state(stub.clone());

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    // Tiny grace period so the listener is accepting before tests fire.
    tokio::time::sleep(Duration::from_millis(20)).await;
    (format!("http://{addr}"), stub)
}

fn build_state(identity_url: String, limit: u32) -> GatewayState {
    let limiter = Arc::new(InMemoryLimiter::new(limit, Duration::from_secs(60)));
    GatewayState::new(reqwest::Client::new(), identity_url, limiter, "test-edge-token")
}

async fn spawn_gateway(state: GatewayState) -> String {
    let app = fastbet_gateway::build_router(state)
        .into_make_service_with_connect_info::<SocketAddr>();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    tokio::time::sleep(Duration::from_millis(20)).await;
    format!("http://{addr}")
}

#[tokio::test]
async fn anonymous_signup_is_forwarded_to_identity() {
    let (identity_url, stub) = spawn_stub_identity().await;
    let gateway_url = spawn_gateway(build_state(identity_url, 5)).await;

    let resp = reqwest::Client::new()
        .post(format!("{gateway_url}/signup"))
        .header("x-forwarded-for", "10.0.0.1")
        .json(&json!({
            "email": "ada@example.com",
            "password": "supersecret123",
            "displayName": "ada"
        }))
        .send()
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::CREATED);
    assert!(resp.headers().get("x-request-id").is_some());
    assert!(resp
        .headers()
        .get("location")
        .and_then(|v| v.to_str().ok())
        .unwrap()
        .starts_with("/users/"));

    assert_eq!(*stub.hits.lock().unwrap(), 1);
    let request_ids = stub.received_request_ids.lock().unwrap();
    assert!(request_ids[0].as_ref().is_some_and(|s| !s.is_empty()));
}

#[tokio::test]
async fn authorization_header_is_not_validated_and_is_stripped() {
    let (identity_url, stub) = spawn_stub_identity().await;
    let gateway_url = spawn_gateway(build_state(identity_url, 5)).await;

    let resp = reqwest::Client::new()
        .post(format!("{gateway_url}/signup"))
        .header("authorization", "Bearer not-a-real-token")
        .header("x-forwarded-for", "10.0.0.2")
        .header("x-user-id", "evil")
        .json(&json!({
            "email": "bob@example.com",
            "password": "supersecret123"
        }))
        .send()
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::CREATED);
    let auth_seen = stub.received_authorization.lock().unwrap();
    assert!(
        auth_seen[0].is_none(),
        "authorization header must be stripped, got {:?}",
        auth_seen[0]
    );
    let user_id_seen = stub.received_user_id.lock().unwrap();
    assert!(
        user_id_seen[0].is_none(),
        "x-user-id must be stripped, got {:?}",
        user_id_seen[0]
    );
}

#[tokio::test]
async fn sixth_request_from_same_ip_is_rate_limited() {
    let (identity_url, stub) = spawn_stub_identity().await;
    let gateway_url = spawn_gateway(build_state(identity_url, 5)).await;
    let client = reqwest::Client::new();

    // First 5 requests from the same IP succeed (or hit Identity, which returns
    // 409 after the first since we re-use the same email — both prove the
    // gateway forwarded them).
    for i in 0..5 {
        let resp = client
            .post(format!("{gateway_url}/signup"))
            .header("x-forwarded-for", "10.0.0.99")
            .json(&json!({
                "email": "rate@example.com",
                "password": "supersecret123"
            }))
            .send()
            .await
            .unwrap();
        let status = resp.status();
        assert!(
            status == StatusCode::CREATED || status == StatusCode::CONFLICT,
            "request {i} should reach identity, got {status}"
        );
    }

    let resp = client
        .post(format!("{gateway_url}/signup"))
        .header("x-forwarded-for", "10.0.0.99")
        .json(&json!({
            "email": "rate@example.com",
            "password": "supersecret123"
        }))
        .send()
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::TOO_MANY_REQUESTS);
    assert!(resp.headers().get("retry-after").is_some());
    assert_eq!(
        *stub.hits.lock().unwrap(),
        5,
        "the rate-limited request must NOT reach Identity"
    );
}

#[tokio::test]
async fn inbound_request_id_is_propagated_downstream() {
    let (identity_url, stub) = spawn_stub_identity().await;
    let gateway_url = spawn_gateway(build_state(identity_url, 5)).await;

    let resp = reqwest::Client::new()
        .post(format!("{gateway_url}/signup"))
        .header("x-forwarded-for", "10.0.0.3")
        .header("x-request-id", "test-rid-42")
        .json(&json!({
            "email": "carol@example.com",
            "password": "supersecret123"
        }))
        .send()
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::CREATED);
    assert_eq!(
        resp.headers()
            .get("x-request-id")
            .and_then(|v| v.to_str().ok()),
        Some("test-rid-42")
    );
    let downstream = stub.received_request_ids.lock().unwrap();
    assert_eq!(downstream[0].as_deref(), Some("test-rid-42"));
}

// --- Edge-token tests (tasks 5.2, 5.3, 5.4) ---

#[tokio::test]
async fn configured_edge_token_is_forwarded_to_identity() {
    // 5.2: A normal POST /signup should arrive at Identity with the
    // gateway-configured X-Edge-Token.
    let (identity_url, stub) = spawn_stub_identity().await;
    let limiter = Arc::new(InMemoryLimiter::new(5, Duration::from_secs(60)));
    let state = GatewayState::new(
        reqwest::Client::new(),
        identity_url,
        limiter,
        "gateway-configured-token",
    );
    let gateway_url = spawn_gateway(state).await;

    let resp = reqwest::Client::new()
        .post(format!("{gateway_url}/signup"))
        .header("x-forwarded-for", "10.0.0.10")
        .json(&json!({
            "email": "edge-fwd@example.com",
            "password": "supersecret123"
        }))
        .send()
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::CREATED);
    let edge_tokens = stub.received_edge_token.lock().unwrap();
    assert_eq!(
        edge_tokens[0].as_deref(),
        Some("gateway-configured-token"),
        "Identity must receive the gateway-configured X-Edge-Token"
    );
}

#[tokio::test]
async fn client_supplied_edge_token_is_replaced_by_gateway_token() {
    // 5.3: A client sending its own X-Edge-Token must NOT have that value
    // forwarded; the gateway's configured token must be used instead.
    let (identity_url, stub) = spawn_stub_identity().await;
    let limiter = Arc::new(InMemoryLimiter::new(5, Duration::from_secs(60)));
    let state = GatewayState::new(
        reqwest::Client::new(),
        identity_url,
        limiter,
        "gateway-token",
    );
    let gateway_url = spawn_gateway(state).await;

    let resp = reqwest::Client::new()
        .post(format!("{gateway_url}/signup"))
        .header("x-forwarded-for", "10.0.0.11")
        .header("x-edge-token", "attacker-token")
        .json(&json!({
            "email": "edge-replace@example.com",
            "password": "supersecret123"
        }))
        .send()
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::CREATED);
    let edge_tokens = stub.received_edge_token.lock().unwrap();
    assert_eq!(
        edge_tokens[0].as_deref(),
        Some("gateway-token"),
        "client-supplied X-Edge-Token must be overwritten with the gateway token"
    );
}

#[test]
fn config_from_env_fails_when_identity_edge_token_is_missing() {
    // 5.4: Config::from_env() must fail fast when IDENTITY_EDGE_TOKEN is absent.
    // Temporarily clear the var if it happens to be set in the test environment.
    let original = std::env::var("IDENTITY_EDGE_TOKEN").ok();
    std::env::remove_var("IDENTITY_EDGE_TOKEN");

    let result = fastbet_gateway::config::Config::from_env();

    // Restore the original value if there was one.
    if let Some(val) = original {
        std::env::set_var("IDENTITY_EDGE_TOKEN", val);
    }

    assert!(result.is_err(), "Config::from_env() must fail when IDENTITY_EDGE_TOKEN is not set");
}

#[test]
fn config_from_env_fails_when_identity_edge_token_is_blank() {
    // 5.4: Config::from_env() must also fail fast when IDENTITY_EDGE_TOKEN is blank.
    let original = std::env::var("IDENTITY_EDGE_TOKEN").ok();
    std::env::set_var("IDENTITY_EDGE_TOKEN", "   ");

    let result = fastbet_gateway::config::Config::from_env();

    match original {
        Some(val) => std::env::set_var("IDENTITY_EDGE_TOKEN", val),
        None => std::env::remove_var("IDENTITY_EDGE_TOKEN"),
    }

    assert!(result.is_err(), "Config::from_env() must fail when IDENTITY_EDGE_TOKEN is blank");
}
