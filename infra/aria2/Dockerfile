FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends aria2 ca-certificates && rm -rf /var/lib/apt/lists/*
EXPOSE 6800
ENTRYPOINT ["aria2c"]
CMD ["--enable-rpc=true", "--rpc-listen-all=true", "--rpc-listen-port=6800", "--dir=/data", "--continue=true", "--auto-save-interval=1", "--file-allocation=none", "--enable-dht=false", "--enable-peer-exchange=false", "--console-log-level=warn"]
