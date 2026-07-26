# Dedicated restore operations

## Configuration

```yaml
dedicated-restore:
  mode: SIDECAR
  restart-script: ""
  sidecar-start-timeout-seconds: 5
  world-release-timeout-seconds: 8
  operation-timeout-seconds: 3600
```

设为 `DISABLED` 会在握手前拒绝还原。相对脚本以服务器工作目录为基准；空值要求自动发现恰好一个候选：Windows 为 `start.bat/start.cmd/run.bat/run.cmd`，Unix 为 `start.sh/run.sh`。

Windows 批处理经 `cmd.exe /c` 执行，Unix shell 经 `/bin/sh` 执行，其他显式文件必须可执行。脚本必须只启动一个服务器实例。

## State files

跨进程状态位于 `plugins/MineBackupPlugin/restart/`：

- `active.properties`：当前原子会话，包含全部已加载世界路径；
- `sidecar.ready`：Sidecar 已订阅 KnotLink 的证据；
- `last-result.properties`：最近一次终态。

新服务器只读取最近结果，绝不会重放旧脚本。发现未完成会话时记录 `UNCERTAIN`。

## Handoff

1. 主进程验证脚本和状态目录，保存玩家及所有世界。
2. 写入会话并启动 Sidecar，异步等待 ready。
3. ready 后踢出玩家并调用正常 Bukkit shutdown。
4. Sidecar 等待父 JVM 退出，并要求所有世界连续三次通过 `session.lock`、`level.dat` 和 region 样本探测。
5. Sidecar 发送一次 `WORLD_SAVE_AND_EXIT_COMPLETE`。
6. 只有 `restore_finished(success/failure)` 或 `restore_cancelled` 允许启动脚本。

明确失败或取消仍会启动服务器，以便原世界或回滚世界重新上线。“重启成功”仅表示脚本进程成功创建，不表示 Minecraft 已完成启动。

## Recovery

- `RESTART_FAILED`：修复脚本后手动启动服务器。
- `UNCERTAIN`：先确认 FolderRewind 已停止写入且世界一致，再手动启动。
- 不要把 KnotLink 静默视为安全失败。
- 禁用面板/wrapper 的立即自动重启，避免绕过 Sidecar 终态门。

## Paper 26.1 diagnostics

- `sun.misc.Unsafe::objectFieldOffset` 且调用方为 Paper 自带的 `org.joml`：这是 Java 25 对服务端依赖的弃用提示，不由 MineBackupPlugin 触发，不代表备份失败。
- `A manual (plugin-induced) save has been detected...`：3.0.0 已在同步保存前临时暂停各世界自动保存，正常情况下不应再由本插件触发；若仍出现，请保留完整时间线并确认是否有其他存档插件调用保存。
- `Corrupt regionfile header detected`：这是数据一致性故障，不能忽略。立即停止写入，保留 Paper 生成的 `.backup`、出问题的 `.mca`、对应 MineBackup 归档、Sidecar 状态文件和 FolderRewind 日志。先在副本上验证归档，再决定回滚或采用 Paper 的修复结果；不要连续覆盖原备份。

区域文件告警只说明“启动后读到的文件已不一致”，单份服务端日志无法区分损坏发生在源世界、备份读取、归档存储还是还原写入阶段。排查时应比较还原前归档中的 region 文件校验值，并核对 FolderRewind 在收到 `WORLD_SAVE_AND_EXIT_COMPLETE` 后才开始覆盖。
