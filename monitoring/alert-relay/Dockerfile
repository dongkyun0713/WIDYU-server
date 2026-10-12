FROM python:3.12-slim
RUN pip install --no-cache-dir anthropic==0.125.0 && mkdir /data && chown nobody /data
WORKDIR /app
COPY relay.py .
USER nobody
# 작업 큐·하루 AI 호출 수·분석 캐시(SQLite)를 볼륨에 둬 재시작해도 유지한다.
VOLUME /data
CMD ["python", "-u", "relay.py"]
