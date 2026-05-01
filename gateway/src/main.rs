use std::sync::Arc;
use std::time::Duration;

use fastbet_gateway::{
    config::Config,
    rate_limit::{RateLimiter, RedisLimiter},
    GatewayState,
};
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let _ = dotenvy::dotenv();

    init_tracing();

    let cfg = Config::from_env()?;
    tracing::info!(?cfg, "starting fastbet-gateway");

    let limiter: Arc<dyn RateLimiter> = Arc::new(
        RedisLimiter::connect(&cfg.redis_url, cfg.signup_rate_limit, cfg.signup_rate_window_secs)
            .await?,
    );

    let http = reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()?;

    let state = GatewayState::new(http, cfg.identity_base_url.clone(), limiter, cfg.identity_edge_token.clone());
    let app = fastbet_gateway::build_router(state)
        .into_make_service_with_connect_info::<std::net::SocketAddr>();

    let listener = tokio::net::TcpListener::bind(&cfg.bind_addr).await?;
    tracing::info!("gateway listening on {}", cfg.bind_addr);
    axum::serve(listener, app).await?;
    Ok(())
}

fn init_tracing() {
    tracing_subscriber::registry()
        .with(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .with(tracing_subscriber::fmt::layer().json())
        .init();
}
