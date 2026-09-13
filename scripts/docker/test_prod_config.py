"""Production Compose regression checks using synthetic values, without a daemon."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("validate_prod", Path(__file__).with_name("validate-prod.py"))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class ProductionComposeTest(unittest.TestCase):
    def config(self, **overrides):
        text = (ROOT / "docker-compose.yml").read_text() + (ROOT / "docker-compose.prod.yml").read_text()
        env = {key: "synthetic-test-value" for key in re.findall(r"\$\{([A-Z0-9_]+)", text)}
        env.update(PATH=os.environ["PATH"], NGINX_HTTP_PORT="80", NGINX_HTTPS_PORT="443",
                   RDS_PORT="3306", REDIS_PORT="6379", MYSQL_PORT="3306",
                   PROD_DOMAIN="prod.example.com", DOCKER_IMAGE_NAME="example/api",
                   IMAGE_TAG="a" * 40, FIREBASE_CREDENTIALS_FILE="/dev/null",
                   JWT_ACCESS_TOKEN_EXPIRATION_TIME="3600",
                   JWT_REFRESH_TOKEN_EXPIRATION_TIME="1209600",
                   JWT_TEMPORARY_TOKEN_EXPIRATION_TIME="1800",
                   COOLSMS_VERIFICATION_CODE_LENGTH="6",
                   COOLSMS_VERIFICATION_CODE_TTL="300")
        env.update(overrides)
        result = subprocess.run([
            "docker", "compose", "--env-file", "/dev/null", "--project-name", "widyu-prod",
            "-f", "docker-compose.yml", "-f", "docker-compose.prod.yml",
            "config", "--format", "json"], cwd=ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, "Synthetic Compose configuration failed")
        return json.loads(result.stdout)

    def test_production_is_isolated_and_uses_schema_validation(self):
        config = self.config()
        validator.validate(config)
        api = config["services"]["widyu-api"]
        self.assertEqual(api["ports"][0]["host_ip"], "127.0.0.1")
        self.assertEqual(api["environment"]["REDIS_HOST"], "redis")
        self.assertNotIn("ports", config["services"]["redis"])
        self.assertEqual(config["volumes"]["certbot_certs"]["name"], "widyu-prod_certbot_certs")
        self.assertEqual(api["environment"]["MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE"], "health,prometheus")
        for name in ("prometheus", "grafana", "loki", "node-exporter", "promtail"):
            service = config["services"][name]
            self.assertFalse(service.get("profiles"))
            self.assertEqual(service["container_name"], f"widyu-{name}-prod")
            for port in service.get("ports", []):
                self.assertEqual(port["host_ip"], "127.0.0.1")
        nginx_mounts = config["services"]["nginx"]["volumes"]
        main = [mount for mount in nginx_mounts if mount["target"] == "/etc/nginx/nginx.conf"]
        self.assertEqual(len(main), 1)
        self.assertTrue(main[0]["source"].endswith("/nginx/prod/nginx.conf"))
        self.assertNotIn("/var/run/docker.sock", str(config["services"]["promtail"].get("volumes", [])))
        loki_mounts = config["services"]["loki"]["volumes"]
        self.assertTrue(any(mount["source"].endswith("loki-config.prod.yml") for mount in loki_mounts))
        nginx_config = (ROOT / "nginx/prod/nginx.conf").read_text()
        self.assertIn("access_log /dev/stdout", nginx_config)
        self.assertIn("error_log /dev/stderr", nginx_config)

    def test_empty_jwt_is_rejected_before_container_replacement(self):
        config = self.config(JWT_ACCESS_TOKEN_SECRET="")
        with self.assertRaisesRegex(ValueError, "JWT_ACCESS_TOKEN_SECRET"):
            validator.validate(config)

    def test_default_grafana_password_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "GRAFANA_ADMIN_PASSWORD"):
            validator.validate(self.config(GRAFANA_ADMIN_PASSWORD="admin"))

    def test_sms_and_video_settings_are_required(self):
        for key in ("COOLSMS_API_KEY", "COOLSMS_API_SECRET", "COOLSMS_PHONE",
                    "FFMPEG_PATH", "FFPROBE_PATH"):
            with self.subTest(key=key):
                with self.assertRaisesRegex(ValueError, key):
                    validator.validate(self.config(**{key: ""}))

    def test_numeric_settings_are_validated_before_replacement(self):
        for key, value, message in (
                ("JWT_ACCESS_TOKEN_EXPIRATION_TIME", "1_000", "positive integer"),
                ("COOLSMS_VERIFICATION_CODE_LENGTH", "not-a-number", "positive integer"),
                ("RDS_PORT", "65536", "between 1 and 65535"),
                ("COOLSMS_VERIFICATION_CODE_TTL", "2147483648", "integer range")):
            with self.subTest(key=key):
                with self.assertRaisesRegex(ValueError, message):
                    validator.validate(self.config(**{key: value}))

    def test_production_proxy_drops_client_forwarded_headers(self):
        template = (ROOT / "nginx/prod/templates/default.conf.template").read_text()
        self.assertNotIn("upstream spring_backend", template)
        self.assertIn("resolver 127.0.0.11", template)
        self.assertNotIn("$proxy_add_x_forwarded_for", template)
        for header in ("Forwarded \"\"", "X-Forwarded-For $remote_addr",
                       "X-Forwarded-Host $server_name", "X-Forwarded-Port $server_port",
                       "X-Forwarded-Prefix \"\"", "X-Forwarded-Ssl on"):
            self.assertGreaterEqual(template.count(header), 2, header)

    def run_prod_up_with_fakes(self, nginx_test_return=0, monitoring_return=0):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            release_root = temporary / "release"
            bin_directory = temporary / "bin"
            bin_directory.mkdir()
            log_file = temporary / "commands.log"
            release_script = release_root / "scripts/docker/prod-up.sh"
            release_script.parent.mkdir(parents=True)
            shutil.copyfile(ROOT / "scripts/docker/prod-up.sh", release_script)
            admin_index = release_root / "admin/dist/index.html"
            admin_index.parent.mkdir(parents=True)
            admin_index.write_text("built admin asset")
            environment_file = temporary / "production.env"
            environment_file.write_text("")
            for command, body in {
                    "docker": "printf '%s\\n' \"$*\" >> \"$FAKE_LOG\"\n"
                              "case \"$*\" in *'nginx nginx -t'*) exit \"${FAKE_NGINX_TEST_RC:-0}\";; "
                              "*'node-exporter prometheus loki promtail grafana'*) exit \"${FAKE_MONITOR_RC:-0}\";; esac\n",
                    "curl": "printf 'curl %s\\n' \"$*\" >> \"$FAKE_LOG\"\n",
                    "flock": "exit 0\n",
                    "python3": "exit 0\n",
            }.items():
                executable = bin_directory / command
                executable.write_text("#!/usr/bin/env bash\nset -eu\n" + body)
                executable.chmod(0o755)
            environment = os.environ | {
                "PATH": f"{bin_directory}:{os.environ['PATH']}", "FAKE_LOG": str(log_file),
                "FAKE_NGINX_TEST_RC": str(nginx_test_return),
                "FAKE_MONITOR_RC": str(monitoring_return), "PROD_ENV_FILE": str(environment_file),
                "PROD_DEPLOY_LOCK_FILE": str(temporary / "deploy.lock"),
                "DOCKER_IMAGE_NAME": "example/api", "IMAGE_TAG": "a" * 40,
                "PROD_DOMAIN": "prod.example.com",
            }
            result = subprocess.run(["bash", str(release_script)], cwd=release_root,
                                    env=environment, capture_output=True, text=True)
            commands = log_file.read_text().splitlines() if log_file.exists() else [result.stderr]
        return result, commands

    def test_monitoring_failure_keeps_api_and_nginx_recovery_complete(self):
        result, commands = self.run_prod_up_with_fakes(monitoring_return=1)
        self.assertNotEqual(result.returncode, 0)
        api = next((index for index, command in enumerate(commands)
                    if "up -d --wait --wait-timeout 240 --no-build --no-deps widyu-api" in command), None)
        self.assertIsNotNone(api, (commands, result.stdout, result.stderr))
        nginx = next(index for index, command in enumerate(commands) if "force-recreate nginx" in command)
        https_health = next(index for index, command in enumerate(commands)
                            if "https://prod.example.com/actuator/health" in command)
        monitoring = next(index for index, command in enumerate(commands) if "node-exporter" in command)
        self.assertLess(api, nginx)
        self.assertLess(nginx, https_health)
        self.assertLess(https_health, monitoring)
        self.assertNotIn("Production deployment succeeded", result.stdout)

    def test_nginx_precheck_failure_stops_before_api_replacement(self):
        result, commands = self.run_prod_up_with_fakes(nginx_test_return=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(any("nginx nginx -t" in command for command in commands), commands)
        self.assertFalse(any("up -d --wait --wait-timeout 240 --no-build --no-deps widyu-api" in command
                             for command in commands))


if __name__ == "__main__":
    unittest.main()
