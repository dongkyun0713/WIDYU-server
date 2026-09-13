# 운영 배포

Amazon Linux 2023 ARM64 / t4g.medium에서 API 한 개를 교체한다. 블루그린은 사용하지 않는다. 결정은 [ADR-0023](adr/ADR-0023-production-single-container-deployment.md)에 기록한다.

## 최초 준비

1. EC2에 Docker Engine, Docker Compose v2.24.4 이상(`!override`, `up --wait`, `--wait-timeout` 지원), Python 3, curl, tar, util-linux(flock)를 설치한다. ec2-user가 Docker를 실행할 수 있도록 준비한다.
2. 운영 DNS를 EC2에 연결한다. 아래 접속·보안 그룹 기준으로 80/443을 공개하고 관리 접속과 배포 접속을 분리한다. RDS는 운영 EC2 보안 그룹에서만 접근하도록 구성한다.
3. /home/ec2-user/.env를 운영 값으로 준비한다. 공통 docker-compose.yml의 환경변수를 기준으로 RDS_ENDPOINT, RDS_PORT, RDS_USERNAME, RDS_PASSWORD, DB_NAME, REDIS_PASSWORD를 지정한다. JWT 변수는 JWT_ACCESS_TOKEN_SECRET, JWT_REFRESH_TOKEN_SECRET, JWT_TEMPORARY_TOKEN_SECRET과 각 EXPIRATION_TIME 이름을 사용한다. 파일을 source하거나 내용을 로그에 출력하지 않는다.
4. NGINX_HTTP_PORT=80, NGINX_HTTPS_PORT=443, SPRING_PROFILES_ACTIVE=prod, REDIS_HOST=redis, REDIS_PORT=6379를 설정한다. RDS TLS를 적용하면 MYSQL_SSL_MODE=VERIFY_IDENTITY를 설정하고 RDS CA를 JVM 신뢰 저장소에 등록한다. Redis가 TLS 종단을 제공하는 구성에서만 REDIS_SSL_ENABLED=true를 설정한다. FFMPEG_PATH와 FFPROBE_PATH는 컨테이너의 /usr/bin/ffmpeg, /usr/bin/ffprobe를 사용한다.
5. FIREBASE_CREDENTIALS_FILE은 Firebase 파일의 절대 경로로 지정하고 파일이 컨테이너 spring 사용자에게 읽히는지 확인한다. OAuth redirect, S3, 결제, SMS, 관리자 계정은 운영 값을 사용한다. private Docker Hub 이미지는 서버에서도 읽기 전용 자격증명으로 docker login을 준비한다.
6. RDS 스냅샷과 복구 절차를 확보한다. 빈 DB는 전체 기준 스키마를 먼저 준비한다. 기존 DB는 결제 멱등 키·PG 트랜잭션 분리·포인트 version·FULLTEXT 인덱스 등의 미적용 변경을 확인해 사전 적용한다. validate는 스키마를 생성하지 않는다.
7. S3 직접 업로드용 CORS와 ETag 노출, 미완료 multipart 1일 후 중단, albums/staging/ 7일 만료 규칙을 확인한다.

실제 변수 이름은 docker-compose.yml의 secrets 절을 함께 확인한다. .env와 Firebase 파일은 릴리스 압축 파일에 포함하지 않는다.

## 관리 접속과 보안 그룹

개인 관리 접속은 Session Manager를 권장한다. SSH의 '내 IP /32'는 등록 당시 공인 IPv4 한 개만 허용하며 자동 갱신되지 않는다. 집·회사·테더링 전환 등으로 공인 IP가 바뀌면 새 주소로 규칙을 수정해야 한다. 서버의 Elastic IP를 고정해도 내 PC의 공인 IP 변경 문제는 해결되지 않는다. [AWS 보안 그룹 기준](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/security-group-rules-reference.html)

| 대상 | 유형·포트 | 인바운드 소스 | 목적·조건 |
| --- | --- | --- | --- |
| 운영 EC2 | HTTP TCP 80 | 0.0.0.0/0 | HTTPS 리다이렉트·인증서 갱신 |
| 운영 EC2 | HTTPS TCP 443 | 0.0.0.0/0 | API·admin 서비스 |
| 운영 EC2 | SSH TCP 22 | 평상시 개인 관리용 규칙 없음 | Session Manager 사용. 현재 SSH 배포를 유지하면 아래 배포 전용 경로가 별도로 필요 |
| 운영 EC2 | 비상 SSH TCP 22 | 현재 관리자 공인 IP /32 | SSM 장애 등 필요할 때만 추가하고 작업 후 제거 |
| 운영 EC2 | TCP 3000/9090/3100/9100/8080/6379 | 인바운드 규칙 없음 | 모니터링·API·Redis 직접 공개 금지 |
| RDS | MySQL TCP 3306 | 운영 EC2 보안 그룹 ID | API의 DB 접속. RDS_PORT 변경 시 실제 포트 적용 |

표는 IPv4 기준이다. IPv6로 서비스하는 경우에도 관리 포트를 전체 공개하지 않는다. SSH에 0.0.0.0/0 또는 ::/0을 허용하지 않는다.

### Session Manager 최초 설정

1. 운영 EC2의 인스턴스 IAM 역할에 AmazonSSMManagedInstanceCore 정책을 추가한다. 기존 애플리케이션용 권한은 보존한다. 이는 서버용 권한이며 관리자의 접속 권한과는 별개다.
2. EC2의 SSM Agent 설치·실행 상태를 확인하고 최신 버전을 유지한다. 서버에서 해당 리전의 Systems Manager 엔드포인트로 HTTPS 443 아웃바운드 통신이 가능해야 한다. 인터넷 경로나 VPC 인터페이스 엔드포인트를 준비하며 EC2 인바운드 443을 SSM용으로 추가할 필요는 없다.
3. 관리자에게 대상 운영 인스턴스와 필요한 세션 문서에 한정한 세션 시작·종료 권한을 부여하고 MFA를 적용한다. 로컬 CLI 이용 시 AWS CLI와 Session Manager 플러그인을 설치하고 AWS 인증을 준비한다.
4. EC2 콘솔에서 인스턴스 → 연결 → Session Manager로 실제 접속을 확인한 뒤 개인 관리용 상시 SSH 규칙을 제거한다. 관리 노드가 오프라인이면 역할·에이전트·DNS·443 아웃바운드 경로를 먼저 확인한다.

사전 조건은 [AWS Session Manager 설정](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-prerequisites.html)을 따른다. 기본 세션 사용자는 ec2-user가 아닌 ssm-user일 수 있다. 이 문서의 배포·파일 준비 명령은 권한을 확인한 뒤 `sudo -iu ec2-user`로 전환해 실행한다.

### GitHub Actions 배포 접속 — 현재 추가 설정 필요

현재 [deploy-prod.yml](../.github/workflows/deploy-prod.yml)은 ubuntu-latest 러너에서 공개 호스트로 ssh/scp를 직접 실행한다. Session Manager 연동이나 보안 그룹 자동 변경은 구현돼 있지 않다. 내 PC IP만 허용하거나 22번을 전부 닫으면 이 워크플로의 업로드·배포는 실패한다.

표준 GitHub-hosted 러너의 IP는 고정된 배포 소스로 취급할 수 없다. GitHub 전체 IP 대역을 SSH 허용 목록에 넣는 것도 권장하지 않는다. [GitHub 러너 네트워크 안내](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)

자동 배포 전 다음 중 한 경로를 선택해 구현·접속 검증해야 한다. 이 문서 수정만으로 적용되지는 않는다.

- 권장 전환안: GitHub OIDC로 제한된 AWS 역할을 받아 SSM을 통해 배포한다. 대상 인스턴스·저장소·production 환경으로 권한을 제한하고, 릴리스 파일 전송과 실행 결과 확인까지 워크플로를 변경한다. 전환 완료 후 배포용 22번 인바운드도 제거할 수 있다.
- SSH 유지안: 고정 송신 IP를 가진 별도 배포 러너를 준비하고 runs-on과 접속 경로를 수정한다. 해당 IP /32 또는 전용 고정 범위만 22번에 허용한다. 운영 앱 EC2를 빌드 러너로 겸용하지 않는다.

배포 경로 전환 전에는 SSH 키가 존재한다는 이유만으로 자동 배포 준비가 완료됐다고 판단하지 않는다. 서버의 SSM 관리 접속 성공과 Actions의 배포 접속 성공을 각각 확인한다.

## 인증서 최초 발급

Nginx를 기동하기 전에 운영 도메인 인증서를 발급한다. 아래 DOMAIN과 EMAIL을 실제 값으로 설정한다. EC2의 80 포트가 비어 있고 DNS가 연결돼 있어야 한다.

```bash
docker volume create widyu-prod_certbot_certs
docker run --rm -p 80:80 \
  -v widyu-prod_certbot_certs:/etc/letsencrypt \
  certbot/certbot:latest certonly --standalone \
  --non-interactive --agree-tos --email "$EMAIL" -d "$DOMAIN"
```

배포 후 갱신은 동일 볼륨과 webroot를 사용한다. 서버 타이머로 매일 실행하고 실패 알림을 연결한다.

```bash
docker run --rm \
  -v widyu-prod_certbot_certs:/etc/letsencrypt \
  -v widyu-prod_certbot_webroot:/var/www/certbot \
  certbot/certbot:latest renew --webroot -w /var/www/certbot --quiet
docker exec widyu-nginx-prod nginx -t &&
docker exec widyu-nginx-prod nginx -s reload
```

## GitHub 설정과 실행

production Environment에 배포 허용 브랜치 정책을 지정한다. 수동 실행 대상으로 승인된 ref만 허용한다. 아래 변수는 현재 SSH 방식 기준이며, 먼저 위 배포 접속 경로를 준비해야 한다. SSM 전환 시 필요한 IAM/OIDC 설정과 변수는 워크플로 변경에 맞춰 갱신한다.

| 종류 | 이름 |
| --- | --- |
| Variable | PROD_DOMAIN |
| Secrets | PROD_EC2_HOST, PROD_EC2_SSH_PRIVATE_KEY, PROD_SSH_KNOWN_HOSTS |
| Secrets | PROD_DOCKER_IMAGE_NAME, DOCKER_USERNAME, DOCKER_HUB_TOKEN |

SSH 호스트 키는 별도 신뢰 경로로 확인해 등록한다. 사용자와 경로는 ec2-user, /home/ec2-user/widyu/releases로 고정한다.

Actions의 Deploy production에서 배포 ref를 선택해 실행한다. 릴리스 Java 전체 정적 검사 → 테스트 → admin 빌드 → ARM 이미지 게시 → 릴리스 업로드 → 인증서와 Nginx 설정 사전 검증 → API 교체/health 대기 → Nginx 교체/HTTPS health 확인 → 모니터링 기동/준비 확인 순서다. 모니터링 준비 실패는 배포를 실패로 표시하지만, 이 시점에는 API와 Nginx가 이미 정상화되어 수동 복원을 막지 않는다. workflow는 기본 브랜치에 반영돼야 수동 실행 목록에 표시된다.

정적 검사는 `bash scripts/docker/validate-release.sh`로 두 backend 모듈의 추적 중인 main Java 소스 전체를 검사한다. 깨끗한 체크아웃에서도 실행되며 기존 코드의 규칙 위반도 배포를 차단한다.

배포 중 진행 중인 영상 처리와 결제를 확인하고 점검 시간을 확보한다. 업로드된 릴리스는 SHA-run ID-attempt 경로에 보관한다. 실패 시 자동 롤백하지 않으며 성공 문구를 출력하지 않는다.

## 운영 모니터링

Prometheus·Grafana·Loki와 기존 수집기 Node Exporter·Promtail을 기본 기동한다. 릴리스에 monitoring 설정을 포함하고 Prometheus/Grafana/Loki 데이터 볼륨은 배포 간 유지한다. 운영 환경 파일에 기본값 admin이 아닌 강한 GRAFANA_ADMIN_PASSWORD를 설정한다. 기존 Grafana 볼륨이 있으면 환경변수 변경만으로 기존 계정 비밀번호가 변경되지는 않으므로 Grafana에서 별도로 변경한다. Nginx는 stdout/stderr로 기록하고 Docker의 20 MiB×5 로그 회전을 적용한다. Loki는 compactor로 30일 보존 후 삭제한다. 이 보존 기간은 단일 t4g.medium의 초기 용량 가정이며, 실제 디스크 사용량을 측정해 조정한다. Prometheus에 디스크 사용량은 수집되지만 이 저장소에는 알림 수신처가 없으므로 디스크 임계치 통지는 별도 Alertmanager/수신처 구성 전까지 구현되지 않는다.

API의 health·prometheus만 내부에 노출한다. Nginx는 health 외 actuator를 차단하며 3000/9090/3100/9100은 호스트 loopback에만 바인딩한다. Grafana는 내 PC에서 SSM 포트 포워딩을 실행한 뒤 http://localhost:3000에서 접근한다. 아래 인스턴스 ID와 리전을 실제 값으로 바꾸고 세션을 유지한다. 로컬 3000번이 사용 중이면 localPortNumber만 변경한다.

```bash
aws ssm start-session \
  --target i-실제인스턴스ID \
  --region 실제리전 \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3000"],"localPortNumber":["3000"]}'
```

이 방식은 EC2의 22번·3000번 인바운드 허용 없이 사용한다. 포트 포워딩 세션의 통신 내용은 Session Manager 세션 로그에 기록되지 않으므로 접속 이력과 서비스 로그를 구분한다. [AWS 세션·포트 포워딩 안내](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-sessions-start.html)

배포는 API 메트릭과 [Prometheus 준비 상태](https://prometheus.io/docs/prometheus/latest/management_api/), [Loki 준비 상태](https://grafana.com/docs/loki/latest/reference/loki-http-api/), Grafana /api/health가 응답해야 성공한다. 이는 수집 완료 검증을 대체하지 않는다. 실서버에서 Prometheus의 widyu-api/node-exporter 타깃이 UP인지, Grafana Explore에서 Prometheus 조회와 새 Loki 로그 조회가 되는지 확인한다. API와 같은 EC2를 사용하므로 메모리·디스크 증가량도 함께 확인한다.

## 장애 확인과 수동 복원

docker logs widyu-api-prod와 docker logs widyu-nginx-prod로 원인을 확인하되 로그의 개인정보와 토큰을 외부에 공유하지 않는다. 이전 릴리스 디렉터리에서 아래를 실행한다. 환경변수는 실제 이전 이미지와 도메인 값으로 지정한다.

```bash
export PROD_ENV_FILE=/home/ec2-user/.env
export DOCKER_IMAGE_NAME=조직/운영이미지
export IMAGE_TAG=이전_40자리_커밋_SHA
export PROD_DOMAIN=운영도메인
bash scripts/docker/prod-up.sh
```

이전 API와 admin 산출물·Nginx 설정이 함께 복원된다. DB가 이전 앱과 호환되는지 먼저 확인한다. down -v 또는 clean.sh로 복원하지 않는다. 일반 prod-down.sh/logs.sh 대신 동일 project-name widyu-prod와 --env-file, 두 Compose 파일을 명시해야 정확한 프로젝트를 대상으로 한다.

## 인수조건

- 개인 IP가 바뀐 네트워크에서도 SSM 관리 접속과 Grafana 포트 포워딩을 확인한다. 상시 개인 SSH 규칙은 두지 않는다.
- 선택한 Actions 배포 접속 경로를 실제로 확인한다. 현재 SSH 워크플로에는 SSM 전환이 미구현이며 내 IP /32만으로는 자동 배포할 수 없다.
- prod는 datasource/FCM/S3 설정을 포함하고 스키마를 자동 변경하지 않는다.
- ARM64 이미지를 SHA로 식별하며 API와 admin은 같은 커밋에서 빌드한다.
- 빌드·테스트·필수 설정·인증서 실패 시 배포를 실패로 종료한다.
- API 교체 시 Redis 및 모니터링 데이터 볼륨을 유지하고 Prometheus·Grafana·Loki와 수집기를 기본 기동한다.
- 릴리스 정적 검사는 깨끗한 체크아웃의 Java 위반도 탐지하며 모니터링 준비 상태 실패 시 배포를 실패로 종료한다.
- Nginx는 운영 도메인·인증서를 사용하고 health 외 actuator 및 Swagger를 공개하지 않는다.
- health 제한 시간 초과는 workflow 실패이며 이전 릴리스로 수동 복원할 수 있다.
- 실서버에서 로그인, 앨범 업로드/영상 처리, WebSocket 재연결, admin, 승인된 결제 검증과 메모리·CPU 크레딧을 확인한다.

LLD: N/A — 도메인 모델·API 계약 변경 없는 배포 설정 작업이다. 릴리스 정적 검사 통과를 위한 Java 정리는 기존 분기와 DTO 생성 경로를 동등한 구현으로 바꾼 리팩터링이며, API·DB 스키마 계약을 변경하지 않는다. 실제 운영 배포 및 DB 변경은 이 코드 작업에 포함하지 않는다.
