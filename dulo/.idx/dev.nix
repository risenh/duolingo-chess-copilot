# Google Project IDX Configuration File (.idx/dev.nix)
# See https://developers.google.com/idx/guides/customize-idx-env for docs

{ pkgs, ... }: {
  channel = "stable-23.11";

  # Enthält das Android SDK und JDK 17
  packages = [
    pkgs.jdk17
    pkgs.gradle
    pkgs.git
  ];

  # Aktiviert den Android-Emulator und die Entwicklungserweiterungen
  idx = {
    extensions = [
      "vscjava.vscode-java-pack"
      "mathiasfrohlich.Kotlin"
    ];

    previews = {
      enable = true;
      previews = {
        android = {
          command = ["./gradlew" "installDebug" "android:start"];
          manager = "android";
        };
      };
    };
  };
}
