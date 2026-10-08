[2026-09-29 01:06] 记录MCP共享Key池架构决策、Key存储架构、2个UI bug修复、1个待修复bug（Key掩码切换）
[2026-09-29 01:39] MCP共享池改为额度刷新语义+手动重置，Key输入框加可见切换
[2026-10-08 00:36] 拉取 origin/dev/refactor 到 a412d7f4，修复 SelectTextField 旧包名导致的 release 编译失败，并清理 videogen/ 与 build-release.log 残留
[2026-10-08 16:52] 约定：gradlew 编译统一加 --no-daemon，避免 daemon 常驻导致 shell 卡住
