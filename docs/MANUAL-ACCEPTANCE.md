# Manual acceptance checklist

- [x] 同一个 JAR 可在 Spigot 1.21.1 / Java 21 启动。
- [x] 同一个 JAR 可在 Paper 1.21.11 / Java 21 启动。
- [x] 同一个 JAR 可在 Paper 26.1.2 / Java 25 启动。
- [x] KnotLink 离线时插件保持启用，`/mb save` 与 `/mb status` 可用。
- [x] 当前世界备份成功、无变化和失败均恢复原自动保存值。
- [x] 自动备份繁忙时跳过，不堆积任务。
- [x] 本地还原倒计时可确认/取消，提交后不可取消。
- [x] FolderRewind 主动还原必须经过新鲜握手和 Sidecar 预检。
- [x] Sidecar 处理全部已加载世界，并在明确成功/失败/取消后启动一次脚本。
- [x] KnotLink 断连、Sidecar 超时或终态缺失时服务器保持离线并记录 `UNCERTAIN`。
- [x] 旧配置被保存为带时间戳文件，并生成 v2 默认配置。
