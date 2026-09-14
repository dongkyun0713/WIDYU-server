#!/usr/bin/env python3
"""Validate resolved Compose without printing credentials."""
import json
import re
import subprocess
import sys


def validate(config):
    api = config["services"]["widyu-api"]
    environment = api["environment"]
    validate_fcm_policy(environment)
    required = (
        "MYSQL_HOST", "MYSQL_PORT", "MYSQL_USERNAME", "MYSQL_PASSWORD", "DB_NAME",
        "REDIS_PASSWORD", "JWT_ACCESS_TOKEN_SECRET", "JWT_REFRESH_TOKEN_SECRET",
        "JWT_TEMPORARY_TOKEN_SECRET", "JWT_ACCESS_TOKEN_EXPIRATION_TIME",
        "JWT_REFRESH_TOKEN_EXPIRATION_TIME", "JWT_TEMPORARY_TOKEN_EXPIRATION_TIME",
        "ADMIN_EMAIL", "ADMIN_PASSWORD", "PAYMENT_SECRET_KEY", "AWS_ACCESS_KEY",
        "AWS_SECRET_KEY", "AWS_REGION", "S3_BUCKET_NAME", "S3_BASE_URL",
        "COOLSMS_API_KEY", "COOLSMS_API_SECRET", "COOLSMS_PHONE",
        "COOLSMS_VERIFICATION_CODE_LENGTH", "COOLSMS_VERIFICATION_CODE_TTL",
        "FFMPEG_PATH", "FFPROBE_PATH", "MEDICINE_API_SERVICE_KEY",
        "JUSO_CONFM_KEY", "KAKAO_GEOCODING_API_KEY",
    )
    missing = [key for key in required if not str(environment.get(key) or "").strip()]
    if missing:
        raise ValueError("Missing production settings: " + ", ".join(missing))
    if environment.get("SPRING_PROFILES_ACTIVE") != "prod":
        raise ValueError("Production profile is required")
    if environment.get("SPRING_JPA_HIBERNATE_DDL_AUTO") != "validate":
        raise ValueError("Production schema validation is required")
    for key in (
        "MYSQL_PORT", "REDIS_PORT", "JWT_ACCESS_TOKEN_EXPIRATION_TIME",
        "JWT_REFRESH_TOKEN_EXPIRATION_TIME", "JWT_TEMPORARY_TOKEN_EXPIRATION_TIME",
        "COOLSMS_VERIFICATION_CODE_LENGTH", "COOLSMS_VERIFICATION_CODE_TTL",
    ):
        raw_value = str(environment.get(key) or "")
        if not re.fullmatch(r"[0-9]+", raw_value):
            raise ValueError(f"{key} must be a positive integer")
        value = int(raw_value)
        if value <= 0:
            raise ValueError(f"{key} must be a positive integer")
        if value > 2147483647:
            raise ValueError(f"{key} exceeds the supported integer range")
        if key.endswith("PORT") and value > 65535:
            raise ValueError(f"{key} must be between 1 and 65535")
    firebase = config.get("secrets", {}).get("firebase-service-account", {})
    if not str(firebase.get("file") or "").strip():
        raise ValueError("FIREBASE_CREDENTIALS_FILE is required")
    password = config["services"]["grafana"]["environment"].get("GF_SECURITY_ADMIN_PASSWORD", "")
    if not password.strip() or password.strip() == "admin":
        raise ValueError("GRAFANA_ADMIN_PASSWORD must be nonempty and not the default")
    webhook = config["services"]["grafana"]["environment"].get("PROD_DISCORD_WEBHOOK_URL", "")
    if not re.fullmatch(r"https://(?:discord\.com|discordapp\.com)/api/webhooks/[0-9]+/[A-Za-z0-9_-]+", webhook):
        raise ValueError("PROD_DISCORD_WEBHOOK_URL must be a Discord HTTPS webhook")


def validate_fcm_policy(environment):
    retries = str(environment.get("FCM_DELIVERY_MAX_RETRIES") or "")
    if not re.fullmatch(r"[0-9]+", retries) or int(retries) >= 2147483647:
        raise ValueError("FCM_DELIVERY_MAX_RETRIES must be a nonnegative supported integer")
    for key in ("FCM_DELIVERY_NORMAL_TTL", "FCM_DELIVERY_EMERGENCY_TTL"):
        value = str(environment.get(key) or "")
        if not re.fullmatch(r"[1-9][0-9]*s", value):
            raise ValueError(f"{key} must be positive integer seconds with an s suffix")
        if int(value[:-1]) > 2419200:
            raise ValueError(f"{key} must not exceed the FCM 28-day lifetime")


if __name__ == "__main__":
    result = subprocess.run(sys.argv[1:] + ["config", "--format", "json"],
                            capture_output=True, text=True)
    if result.returncode:
        sys.exit("Compose validation failed; check environment names and file paths locally.")
    try:
        validate(json.loads(result.stdout))
    except (ValueError, KeyError) as error:
        sys.exit(str(error))
