# ClassSync multi-stage image.
#   target "build": compiles and runs tests   -> docker compose --profile test run --rm build-test
#   target "gui":   native JavaFX desktop app  -> docker compose --profile gui up classsync-gui
# No passwords or uploads are baked into any image; they arrive via environment/volumes.

# ---------- build / test ----------
FROM maven:3.9-eclipse-temurin-21-noble AS build
WORKDIR /app
COPY pom.xml ./
# cache dependencies in their own layer
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY database ./database
# compile + package; tests run when the container starts (they need the database).
# Unit tests (no database) run during the image build; running one database-free test class through
# failsafe as well makes Maven download both JUnit providers now, so `build-test` needs no internet later.
RUN mvn -B -q package \
 && mvn -B -q failsafe:integration-test -Dit.test=QuizRulesTest \
 && mvn -B -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
CMD ["mvn", "-B", "verify"]

# ---------- desktop GUI runtime ----------
FROM eclipse-temurin:21-jre-noble AS gui
# Native libraries required by OpenJFX on Linux (GTK3, OpenGL/Mesa software rendering, X11 extensions, fonts)
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libgtk-3-0t64 libgl1 libgl1-mesa-dri libglx-mesa0 libxtst6 libxxf86vm1 libxi6 libxrender1 \
      fonts-dejavu-core fonts-noto-color-emoji \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --create-home --uid 10001 classsync \
 && mkdir -p /data/uploads && chown classsync /data/uploads
WORKDIR /opt/classsync
COPY --from=build /app/target/classsync-*.jar ./classsync.jar
COPY --from=build /app/target/lib ./lib
USER classsync
# Software rendering works on X11 and WSLg without GPU passthrough
ENV JAVA_TOOL_OPTIONS="-Dprism.order=sw -Dprism.verbose=false"
CMD ["java", "-cp", "classsync.jar:lib/*", "com.classsync.Launcher"]
