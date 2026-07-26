# MineBackupPlugin

MineBackupPlugin 是 MineBackup/FolderRewind 的 Spigot、Paper 服务端联动版本。插件不自行存储备份；它负责安全保存 Minecraft 世界、通过 KnotLink v2 请求 FolderRewind 执行备份，并在还原时把停服后的文件所有权交给独立 Sidecar。

## 支持范围

- Minecraft `1.21.1`～`26.1.2`
- Spigot、Paper 及保持 Bukkit/Spigot API 兼容的服务端
- 一个 Java 21 字节码 JAR；Minecraft 26.1 服务端可在 Java 25 上运行同一 JAR
- FolderRewind `1.14.0` 或更高版本
- KnotLink SDK 2.0 魔数帧格式，仅连接本机 `127.0.0.1:6372/6376`

KnotLink 不可用时插件仍会加载并自动重连，`/mb save` 仍可用；依赖 FolderRewind 的命令会返回通信错误。

## 安装

1. 将 JAR 放入服务端 `plugins` 目录。
2. 启动服务器，检查 `plugins/MineBackupPlugin/config.yml`。
3. 确保 FolderRewind、Minecraft 专用扩展和 KnotLink 正在同一台计算机运行。
4. 执行 `/mb status` 检查连接和 Sidecar 状态。

从 2.x 升级时，旧配置不会被猜测性迁移。插件会将其保存为 `config-v1-backup-时间.yml`，然后生成带有 `config-version: 2` 的新配置。

## 命令

所有命令只使用权限 `minebackup.command`，默认仅 OP 拥有。

| 命令 | 说明 |
|---|---|
| `/mb help` | 显示帮助 |
| `/mb status` | 显示 KnotLink、当前操作、自动保存、调度和 Sidecar 状态 |
| `/mb save` | 保存全部玩家及全部已加载世界 |
| `/mb backup [comment]` | 备份当前世界 |
| `/mb restore [backup-file]` | 使用指定文件或最新备份还原当前世界 |
| `/mb confirm` | 立即提交倒计时中的还原 |
| `/mb stop` | 取消尚未提交的还原倒计时 |
| `/mb list configs` | 列出 FolderRewind 配置 |
| `/mb list folders <config-id>` | 列出指定配置的文件夹 |
| `/mb list backups <config-id> <folder>` | 列出备份文件 |
| `/mb target backup <config-id> <folder> [comment]` | 备份非当前世界目标 |
| `/mb auto start <minutes>` | 启动当前世界定时备份 |
| `/mb auto stop` | 停止定时备份 |
| `/mb reload` | 原子重载插件配置 |

带空格的文件夹、文件名或注释可使用双引号，双引号和反斜杠可用 `\` 转义。3.0 不提供旧命令别名，也不允许任意目标还原；所有还原必须走当前世界操作门和 Sidecar。

## 还原安全

`dedicated-restore.mode` 默认为 `SIDECAR`。还原前插件会验证唯一启动脚本、保存全部世界并启动纯 JDK Sidecar；Sidecar 确认已经订阅 KnotLink 后，服务器才会踢出玩家并正常关闭。

Sidecar 只有在父 JVM 已退出、所有已加载世界连续三次确认释放，并收到 FolderRewind 明确的成功、失败或取消终态后，才启动一次服务器脚本。断连、超时或未知结果会记录为 `UNCERTAIN` 并保持服务器离线。

不要同时启用面板或 wrapper 的“进程退出立即重启”，否则可能在 FolderRewind 写入世界时抢先启动。完整流程与故障恢复见 `docs/DEDICATED-RESTORE.md`。

## 构建

```text
./gradlew clean test build
```

默认针对最低 Spigot API 构建。兼容性编译可使用：

```text
./gradlew test -PspigotApiVersion=1.21.11-R0.1-SNAPSHOT
./gradlew test -PspigotApiVersion=26.1.2-R0.1-SNAPSHOT
```

架构说明见 `docs/ARCHITECTURE.md`，第三方来源见 `THIRD-PARTY-NOTICES.md`。
