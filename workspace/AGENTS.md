# Workspace 模块 AGENTS.md

本模块是各 workspace 沙盒的「文件系统 + shell 执行」边界层。
上层（app 侧 AI 工具）只能经 `WorkspaceManager` 访问文件与命令，不得直触 `File`。

## 核心文件/类职责

- `Workspace.kt`: 数据模型（`Workspace`/`WorkspaceFileEntry`/`WorkspaceCommandResult`），`WorkspaceConfig` 限额（读 512KB/写 2MB/列表 500/搜索 100），`FILES`/`LINUX` 分区。
- `WorkspaceManager.kt`: 唯一入口。维护 `{root}/files|linux|tmp` 布局，提供 list/read/write/import/export/delete/move/glob/grep/`executeCommand`，以及 rootfs 路径映射 `resolveRootfsPath`。
- `WorkspaceFileSystem.kt`: 路径解析与配额执行者。`resolvePath` 做 canonical 归一化与越界校验，所有读写删改都经它。
- `WorkspaceShellRunner.kt`: `HostShellRunner`（`sh -c` 直跑）+ `Process.readResult`（超时强杀、128KB 输出截断、stdin 关闭语义）。
- `ProotShellRunner.kt`: Linux 沙盒执行器。固定参数 `--root-id --link2symlink --kill-on-exit`，挂载 files→`/workspace`，设置 `PROOT_LOADER`/`TMPDIR`。
  无可用 rootfs（缺 `bin/sh`）或缺 proot/loader 时直接返回 exit 127，不起进程。
- `RootfsInstaller.kt` / `RootfsPatcher.kt`: rootfs 下载→staging 解包→整体 rename 到 `linux/`→写 DNS/hosts/hostname；失败不留半成品。
- bind mount 按 target 长度降序最长前缀匹配；`/workspace` 映射到 files 区；`cleanupAllTempDirs` 只清 tmp 与 rootfs 内 tmp。

## 安全注意（按实际代码）

- 路径越界：`resolvePath` 用 `canonicalFile` 前缀比对，越界抛 `Path escapes workspace root`；root 名仅允 `[A-Za-z0-9._-]+`；删/mv workspace 根被直接拒绝。
- 命令执行：`command` 以整串进 `sh -c`，本层不做转义，调用方禁拼接不可信输入；默认超时 30s（`DEFAULT_COMMAND_TIMEOUT_MS`），超时 `destroyForcibly` 返回 exit -1/`timedOut`；单流超 128KB 截断。
- 敏感路径：`/dev|/proc|/sys` 禁文件直读（只能走 shell）；bind mount target 必须绝对路径；`delete` 目录必须显式 `recursive=true`。
  `executeCommand` 的 cwd 不存在或非目录直接拒绝；list/glob/grep 过滤 `.l2s.*` 内部文件，grep 跳过超读限额文件。
- 何时改动需谨慎：动 `resolvePath`/`requireValidRoot`/`resolveRootfsPath`、超时与截断常量、proot 参数与 env、`patcher` 的 etc 写入、`installer` 的 staging-rename 流程时，必须先跑 `RootfsPathResolutionTest`/`RootfsInstallerTest`。
