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
