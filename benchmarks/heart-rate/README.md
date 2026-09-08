# 심박 부하 테스트 결과

`scripts/k6/run_heart_rate_benchmark.sh`가 실행 시각과 환경 이름별 하위 디렉터리에 원본 결과를 생성한다.
의사결정에 사용한 실행 디렉터리는 환경 파일에서 시크릿을 제거한 뒤 이 경로에 커밋한다.

결과 해석과 필수 환경 정보는 `docs/lld/LLD-0026-heart-rate-load-test.md`를 따른다.
