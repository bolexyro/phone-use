FROM node:22-bookworm-slim

WORKDIR /app

RUN corepack enable
ENV ELECTRON_SKIP_BINARY_DOWNLOAD=1

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml tsconfig.base.json ./
COPY config ./config
COPY packages/phone-control ./packages/phone-control
COPY apps/phone-control-mcp ./apps/phone-control-mcp

RUN pnpm install --frozen-lockfile
RUN pnpm --filter @dhd/phone-control build && pnpm --filter @dhd/phone-control-mcp build

CMD ["pnpm", "start"]
