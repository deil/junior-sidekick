FROM debian:trixie-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV MISE_CACHE_DIR=/work/.mise/cache
ENV MISE_CONFIG_DIR=/work/.mise/config
ENV MISE_DATA_DIR=/work/.mise/data
ENV MISE_INSTALL_PATH=/usr/local/bin/mise
ENV MISE_STATE_DIR=/work/.mise/state

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        bind9-dnsutils \
        ca-certificates \
        coreutils \
        curl \
        findutils \
        gawk \
        git \
        grep \
        jq \
        less \
        netbase \
        nodejs \
        npm \
        iputils-ping \
        procps \
        python3 \
        python3-pip \
        sed \
        tar \
        unzip \
        whois \
        xz-utils \
        zip \
        ibglib2.0-0 libnss3 libnspr4 libatk1.0-0 \
        libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 libatspi2.0-0 \
        libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 \
        libpango-1.0-0 libcairo2 libasound2 libxshmfence1 \
    && curl https://mise.run | sh \
    && curl -LsSf https://astral.sh/uv/install.sh | sh \
    && sed -i 's#^nobody:\([^:]*\):65534:65534:[^:]*:[^:]*:[^:]*#sidekick:\1:65534:65534:Sidekick:/work:/bin/bash#' /etc/passwd \
    && sed -i 's#^nogroup:\([^:]*\):65534:#sidekick:\1:65534:#' /etc/group \
    && mkdir -p /data/session /data/skills /data/global /data/project /work \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work
