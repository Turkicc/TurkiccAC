name: Build plugin jar

# Builds SentinelAC on GitHub's servers (which can reach the Paper + PacketEvents
# repositories) and uploads the finished .jar as a downloadable artifact.
#
# Runs automatically on every push, and you can also trigger it by hand from the
# "Actions" tab via the "Run workflow" button (workflow_dispatch).

on:
  push:
  pull_request:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Check out the code
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: Build with Maven
        # -B = non-interactive, -U = refresh dependencies.
        run: mvn -B -U clean package

      - name: Upload the jar
        uses: actions/upload-artifact@v4
        with:
          name: SentinelAC-jar
          path: target/*.jar
          if-no-files-found: error
