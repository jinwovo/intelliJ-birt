# intelliJ-birt

[English](README.md) | [한국어](README.ko.md) | [日本語](README.ja.md) | 简体中文

一个 **非官方** 的 IntelliJ IDEA 插件(插件名:*Report Designer for BIRT*),可以像在 Eclipse BIRT 设计器中一样编辑
Eclipse BIRT 4.8 报表(`*.rptdesign`)。插件内置了 Eclipse BIRT 4.8 设计器本身,因此无需另外安装 Eclipse、Java 或 BIRT
(使用 IntelliJ 自带的 Java 运行)。

> 目前插件的菜单和提示信息为韩语。

| 功能 | 说明 |
|---|---|
| 编辑器标签页 | 底部标签页 Layout · Master Page · Script · XML Source。Layout 和 Master Page 就是 Eclipse BIRT 原版编辑器(调色板、标尺、表格手柄、右键菜单、拖动调整大小) |
| 工具窗口 | BIRT Property Editor(下方)、BIRT Data Explorer(左侧)、BIRT Outline 和 BIRT Resource Explorer(右侧) — 即 Eclipse 原版视图。仅在打开报表设计器标签页时显示 |
| 同步 | 在设计器或 Property Editor 中的修改会立即反映到 XML Source。编辑 XML Source 后切回 Layout 时,会像 Eclipse 一样询问是否保存 |
| 保存 | 与 Eclipse 相同,只在按 Ctrl+S 时保存(不自动保存)。未保存的标签页标有 `*`,关闭时询问是否保存 |
| 快捷键 | 设计器内的 Ctrl+Z · Y · C · X · V · A · S |
| 结构视图与高亮 | 数据源(仅连接 URL)、数据集、参数、正文;SQL 和 JavaScript 语法高亮 |
| 校验与预览 | 用 BIRT 4.8 引擎校验已保存的文件(在对应行显示错误)。右键 > BIRT 预览,可生成 PDF、HTML 或其他格式(DOC、DOCX、PPT、PPTX、ODT、ODS、PostScript) — 与 Eclipse 的 Run > View Report 相同的格式 |

BIRT 4.8 设计器编辑器内的 Preview 标签页已被 BIRT 自身禁用(Bugzilla 421879),因此本插件也没有该标签页。请使用上述预览菜单。

## 版本(Releases)

| 版本 | 文件 | 大小 | 数据源 | 输出格式 |
|---|---|---|---|---|
| 完整版 | `intellij-birt-<版本号>.zip` | 约 145 MB | 与官方设计器相同 — JDBC、Flat File(CSV)、Excel、XML、Web Services、MongoDB、Hive、POJO、脚本、示例数据库(Classic Models) | PDF、HTML、DOC、DOCX、PPT、PPTX、ODT、ODS、PostScript |
| 精简版 | `intellij-birt-lite-<版本号>.zip` | 约 123 MB | JDBC、POJO、脚本 | 除 DOCX 外相同 |

两个版本的编辑器、工具窗口、校验和保存功能完全相同。请只安装其中一个(两者插件 ID 相同,安装另一个会将其替换)。
用精简版打开使用了完整版专有数据源的报表时,校验会将该数据源报告为 "invalid manifest" 错误,且无法预览。

## 安装

Windows,IntelliJ IDEA 2025.1 或更高版本(已在 2025.1、2026.1、2026.2 上验证兼容性)
> Settings > Plugins > ⚙ > Install Plugin from Disk > 下载的 zip(无需解压) > 重启

## 设置(Settings > Tools > BIRT 4.8) — 所有项留空也能正常使用

| 项目 | 默认值 |
|---|---|
| Java | IntelliJ 自带的 Java |
| 预览允许的数据库 | 无(不限制)。设置后,只运行连接到这些地址的报表 |
| 预览禁止的数据库 | 无。设置后,连接到这些地址的报表不会预览,数据集的 Preview Results 也会被阻止 |
| JDBC 驱动 jar | 无。若报表所在项目的 `WEB-INF/lib` 中有驱动,则使用它 |
| (可选)Eclipse | 无 |

连接信息使用报表文件中写明的内容。与 Eclipse BIRT 设计器一样,JDBC 驱动从包含该报表的项目的 `WEB-INF/lib`
(Web 项目的 Web App Libraries)中查找。**插件本身不包含任何数据库驱动。** 从 `WEB-INF/lib` 中按文件名挑选驱动
(`jdbc`、`mysql`、`mariadb`、`postgresql`、`mssql`、`db2jcc`、`jtds`、`tibero`、`altibase`、`cubrid`、`ngdbc`),
其他驱动请在设置中或通过设计器的 Manage Drivers 添加。

允许/禁止列表也可以写在报表上层文件夹中的 `.intellij-birt.properties` 文件里(从报表向上查找到的第一个文件,与 IDE 设置合并)。
把这个文件提交到仓库,就能共享团队规则(例如禁止连接生产数据库),无需每个人单独配置 IDE。

```properties
preview.allowHosts=devdb:1521
preview.denyHosts=proddb:1521
```

### 数据库保护的范围

- 预览(所有输出格式):根据报表的连接 URL(`odaURL`)判断 — 适用于所有数据库
- 设计器中的连接(数据集 Preview Results、获取列、Test Connection):仅限以下驱动(因为 BIRT 按驱动类名查找连接工厂) —
  Oracle、MySQL、MariaDB、PostgreSQL、SQL Server、jTDS、Db2、SAP HANA、Tibero、Altibase、CUBRID
- 这是基于连接 URL 字符串比较的防误操作机制,并非安全边界(例如无法阻止在脚本中修改 URL 的报表)

### 注意事项

- 打开报表时会加载该项目 `WEB-INF/lib` 中的驱动 jar,预览时会执行报表中的脚本(与 Eclipse BIRT 相同)。
  来源不明的项目或报表,请先检查内容再打开。
- 工作文件只放在 IDE 系统文件夹下的 `intellij-birt/` 中。服务器临时文件(`tmp/`)在服务器启动和停止时删除,
  预览结果(`preview/`)在 IDE 退出时删除。

## 工作原理

插件在单独的 JVM 中用 `designer/` 文件夹里的 Eclipse 插件包启动 OSGi(设计器服务器,`BirtDesignerServer`),
并把该服务器打开的 BIRT 编辑器和视图的窗口嵌入到 IntelliJ 的编辑器标签页和工具窗口中(Windows 窗口重设父窗口)。
命令通过标准输入逐行发送,应答以标准输出中的 `@@ ` 行返回。不打开任何网络端口。最后一个报表编辑器关闭 1 分钟后服务器停止。

| 路径 | 内容 |
|---|---|
| `intellij/` | IntelliJ 插件 — 编辑器标签页和工具窗口(`BirtEmbeddedEditor`、`BirtViewWindows`)、同步(`BirtDesignerSync`)、保存(`BirtSaving`)、结构视图、校验与预览(`BirtCliRunner`) |
| `render/` | 设计器服务器的 OSGi 插件包 — 命令处理(`App`)、嵌入编辑器和视图(`EmbeddedEditors`、`EmbeddedViews`)、数据库保护(`GuardedConnectionFactory`) |
| `render/bundles.txt` | 插件中包含的 Eclipse 插件包列表(精简版) |
| `render/bundles-full.txt` | 完整版额外包含的插件包(数据源、DOCX 输出) |
| `cli/src` | 校验、参数、预览(按输出格式) — 与 render 插件包一起编译,在服务器内运行 |

插件 zip 几乎全部由 Eclipse BIRT 设计器的插件包组成。

## 构建

- 安装了 BIRT 4.8 设计器的 Eclipse(从中复制插件包)和 JDK 8(用于 `render`,由 Gradle toolchain 查找)。
  IntelliJ SDK 使用支持范围内的最低版本(2025.1),由 Gradle 下载
- 将 `local.properties.example` 复制为 `local.properties`,设置 `eclipsePlugins`(必填)和 `ideaPath`(可选 — 加入兼容性检查的已安装 IDE)
- 用 IntelliJ 自带的 JBR 运行 Gradle
- 放在 Eclipse JDBC 插件包 `drivers` 文件夹中的驱动 jar 不会被打包进插件

```
set JAVA_HOME=C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr
gradlew :intellij:buildPlugin                    (完整版)
gradlew :intellij:buildPlugin -Pedition=lite     (精简版)
gradlew :intellij:verifyPlugin                   (兼容性检查:2025.1、2026.1、已安装的 IDE)
```

输出:`intellij/build/distributions/intellij-birt-<版本号>.zip`、`intellij-birt-lite-<版本号>.zip`

## 许可证

- 本仓库的代码:MIT 许可证([LICENSE](LICENSE)),但 `render/java-modern.profile` 修改自 Eclipse Equinox 的文件,仍为 EPL-1.0
- 内置的第三方组件及其许可证:[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)(发布 zip 中也包含),EPL-1.0 全文:[licenses/EPL-1.0.html](licenses/EPL-1.0.html)
- 发布 zip 中包含的 Eclipse BIRT 4.8 插件包及其依赖均为未修改的原版,遵循各插件包 `about.html` 中所列的许可证
  (主要为 EPL-1.0 和 Apache-2.0)。BIRT 源码:https://github.com/eclipse-birt/birt(4.8 版本,构建 v201806261756);
  Eclipse 平台和 DTP 的源码见各插件包的 `about.html`。
- Eclipse 和 BIRT 是 Eclipse Foundation 的商标。本项目与 Eclipse Foundation 无关。
