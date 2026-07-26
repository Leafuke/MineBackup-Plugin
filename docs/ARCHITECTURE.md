# Architecture

## Ownership

`MineBackupPlugin` 是组合根，拥有配置、KnotLink、操作协调器、世界保存控制器、自动调度器、Sidecar 管理器和审计日志。停用时按依赖顺序关闭，不使用可跨重载存活的全局任务状态。

## Thread boundaries

- Bukkit 玩家、世界、消息、踢出和停服 API 只在服务器主线程调用。
- KnotLink Socket、Sidecar ready 等待、配置写入和定时调度不阻塞主线程。
- 网络回调必须先通过 Bukkit 调度器再触碰服务器对象。

## Mutation gate

本地保存、当前世界备份、目标备份、自动备份和还原共享一个变更操作门。每个操作拥有 UUID、来源、执行者、目标、阶段和终态。不匹配 UUID 的信号不能结束当前操作；FolderRewind 未提供 UUID 的广播只能作用于唯一活动操作。

## Backup flow

1. 提交严格 v2 `BACKUP` 请求。
2. 接受版本兼容且五秒内有效的 `handshake`。
3. `pre_hot_backup` 后在主线程保存玩家及所有世界。
4. 记录每个世界的自动保存值并冻结，然后发送 `WORLD_SAVED`。
5. 匹配的成功、失败、超时或停用恢复原值并结束操作。

## Restore flow

本地请求经过可取消倒计时；FolderRewind 主动请求在严格握手与 Sidecar 预检后直接接管。两者都只能还原当前世界，并最终进入 `docs/DEDICATED-RESTORE.md` 定义的跨进程交接。

## Protocol

KnotLink 固定使用回环地址和标准端口。TCP 帧为 `4B 4B 00 02`、四字节大端长度及严格 UTF-8 payload。请求字段采用 RFC 3986 percent-encoding，拒绝重复/非法键和旧 `OK:/ERROR:` 响应。
