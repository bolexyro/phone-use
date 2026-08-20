FROM node:22-bookworm-slim

WORKDIR /app

RUN corepack enable
ENV ELECTRON_SKIP_BINARY_DOWNLOAD=1

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY tsconfig.json vitest.config.ts ./
COPY config ./config
COPY src ./src
COPY tests ./tests

RUN pnpm typecheck && pnpm test && pnpm build

CMD ["pnpm", "start"]
