# MineBackup-Plugin

MineBackup-Plugin 是一款基于 Minecraft 1.21 的 Spigot 插件。作为 [MineBackup](https://github.com/Leafuke/MineBackup/releases)/[FolderRewind](https://github.com/Leafuke/FolderRewind/releases) 的联动组件，它通过 [KnotLink](https://github.com/hxh230802/KnotLink) 协议与主程序进行通信，实现跨端的数据同步与信号广播。

## 🌟 特性

- **KnotLink 协议支持**：通过 TCP 长连接（默认端口 `6372`）订阅 MineBackup 主程序的广播信号，实现低延迟的跨端通信。
- **完全兼容 1.21.x**：基于最新的 Spigot API 1.21 开发，支持 Java 21。
- **轻量级架构**：专注于信号订阅与指令转发，不占用过多服务器资源。

## 📦 环境要求

- **Minecraft 版本**：1.21.x
- **服务端核心**：Spigot / Paper 及其分支
- **Java 版本**：Java 21 或更高版本
- **前置依赖**：需要运行 MineBackup/FolderRewind+MineRewind 主程序（提供 KnotLink 信号源）

## 🚀 安装与使用

1. 在 Releases 页面下载最新版本的 `.jar`。
2. 将下载的 `.jar` 文件放入服务器的 `plugins` 文件夹中。
3. 启动或重启服务器。
4. 确保 MineBackup 主程序已启动，并且确保电脑安装了 [KnotLink 服务端](https://github.com/hxh230802/KnotLink/releases)。

## ⌨️ 指令与权限

| 指令 | 参数 | 描述 |
| :--- | :--- | :--- |
| **/mb save** | (无) | 在游戏内手动执行一次完整的世界保存，效果等同于 `/save-all`。 |
| **/mb list_configs** | (无) | 列出你在 MineBackup 主程序中设置的所有配置方案及其ID。 |
| **/mb list_worlds** | `<config_id>` | 列出指定配置下的所有世界及其索引号（index）。 |
| **/mb list_backups** | `<config_id> <world_index>` | 列出指定世界的所有可用备份文件。 |
| **/mb backup** | `<config_id> <world_index> [注释]` | 命令主程序为指定世界创建一次备份。可以附上一段可选的注释。 |
| **/mb restore** | `<config_id> <world_index> <文件名>` | 命令主程序用指定的备份文件来还原世界。如果你希望还原当前世界，务必使用 **/mb quickrestore** |
| **/mb auto** | `<config_id> <world_index> <internal_time>` | 请求 MineBackup 执行自动备份任务，间隔 internal_time 分钟进行自动备份 |
| **/mb stop** | `<config_id> <world_index>` | 请求 MineBackup 停止自动备份任务 |
| **/mb quicksave** | `[注释]` | 为当前世界执行备份 |
| **/mb quickrestore** | `[文件名]` | 为当前世界执行热还原，不填写文件名则自动选择最新的备份文件 |

**插件与模组的具体执行行为存在差异**，使用过程中务必留意。