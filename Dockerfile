# 构建镜像只装 JDK 与 Gradle。Android SDK **不在镜像里下载**，而是运行时由
# link-vault-sdk 卷挂载到 /opt/android-sdk 提供。
#
# 原因：本机网络下 dl.google.com 不可达（curl exit 35，SSL unexpected eof），
# 把 SDK 打进镜像的那一步必然失败，导致整个镜像构建不出来、构建链路完全断掉。
# 挂载卷的另一个好处是换机器时不必重下 462MB 的 SDK，见 env-transfer.sh。
#
# 新机器首次准备 SDK 卷：
#   从已有机器导出并在新机器导入 —— ./env-transfer.sh export / ./env-transfer.sh import
FROM --platform=linux/amd64 mcr.microsoft.com/openjdk/jdk:17-ubuntu@sha256:b50804317051eaacb2d31410ed32c3856cac3048854f65fed9de9f52ad788155
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates && rm -rf /var/lib/apt/lists/*
RUN curl -fL --retry 3 --connect-timeout 20 --max-time 900 https://downloads.gradle.org/distributions/gradle-8.9-bin.zip -o /tmp/gradle.zip \
 && echo "d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab  /tmp/gradle.zip" | sha256sum -c - \
 && unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=/opt/gradle-8.9/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/build-tools/34.0.0:$PATH
WORKDIR /project
CMD ["bash", "/project/build-in-container.sh"]
