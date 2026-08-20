FROM node:22-bookworm-slim

WORKDIR /app

RUN corepack enable

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

COPY tsconfig.json vitest.config.ts ./
COPY config ./config
COPY src ./src
COPY tests ./tests

RUN pnpm typecheck && pnpm test && pnpm build

CMD ["pnpm", "start"]
