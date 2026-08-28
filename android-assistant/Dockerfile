FROM ghcr.io/cirruslabs/android-sdk:35

WORKDIR /workspace

COPY . .

RUN chmod +x gradlew && \
    ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
