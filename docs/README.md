# 文档

这个目录放所有「不属于代码」的东西：调研记录、历史发版说明、截图。

## 目录结构

| 路径 | 内容 |
|---|---|
| `GRADUATE_SUPPORT_PLAN.md` | 研究生课表接入的调研记录（两套系统的差异、接口实测结论） |
| `MINIPROGRAM_MIGRATION_PLAN.md` | 微信小程序迁移的可行性调研（**已搁置**，当前重心在 Android） |
| `release-notes/` | v1.2.1 ~ v1.2.6 的详细发版说明 |
| `screenshots/` | README 里用的界面截图 |

## 关于发版说明

**从 v1.2.7 起不再单独建 md 文件**，历史版本也不用在这里补。

版本日志的唯一来源是 `app/src/main/java/com/gzucm/pocket/core/ReleaseNotes.kt`：

- App 内「设置 → 关于软件 → 更新日志」读的就是它
- GitHub Release 的正文按它的措辞写一遍

两处会不一致吗？`ReleaseNotesTest` 会校验最新条目的版本号等于 `BuildConfig.VERSION_NAME`，漏写会直接测试失败。所以 App 里那份一定是全的。

`release-notes/` 里这几个旧文件是历史遗留，保留只为留存当时的详细写法（有配图、有设计取舍的说明），不是必读。

## 为什么根目录这么干净

根目录只留 Gradle 项目本身需要的文件：

```
build.gradle.kts  settings.gradle.kts  gradle.properties
gradlew  gradlew.bat  .gitignore  LICENSE  README.md
```

文档一律进 `docs/`，免得根目录越堆越长。