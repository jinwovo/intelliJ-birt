# intelliJ-birt

English | [한국어](README.ko.md) | [日本語](README.ja.md) | [简体中文](README.zh-CN.md)

An **unofficial** IntelliJ IDEA plugin (plugin name: *Report Designer for BIRT*) for editing Eclipse BIRT 4.8 reports (`*.rptdesign`)
exactly as in the Eclipse BIRT designer. The Eclipse BIRT 4.8 designer itself ships inside the plugin, so there is nothing else to install —
no Eclipse, no separate Java, no BIRT runtime (it runs on the Java bundled with IntelliJ).

> The plugin's menus and messages are currently in Korean.

| Feature | Description |
|---|---|
| Editor tabs | Bottom tabs Layout · Master Page · Script · XML Source. Layout and Master Page are the original Eclipse BIRT editors (palette, rulers, table handles, context menus, drag-to-resize) |
| Tool windows | BIRT Property Editor (bottom), BIRT Data Explorer (left), BIRT Outline and BIRT Resource Explorer (right) — the original Eclipse views. Shown only while a report designer tab is open |
| Sync | Changes made in the designer or Property Editor appear in XML Source immediately. After editing XML Source, switching back to Layout asks whether to save, just like Eclipse |
| Saving | Eclipse-style: only on Ctrl+S (no auto-save). Unsaved tabs are marked with `*`; closing asks whether to save |
| Shortcuts | Ctrl+Z · Y · C · X · V · A · S inside the designer |
| Structure view & highlighting | Data sources (connection URL only), data sets, parameters, body; SQL and JavaScript highlighting |
| Validation & preview | Saved files are validated with the BIRT 4.8 engine (errors shown on their lines). Right-click > BIRT preview as PDF, HTML, or other formats (DOC, DOCX, PPT, PPTX, ODT, ODS, PostScript) — the same formats as Eclipse's Run > View Report |

The in-editor Preview tab of the BIRT 4.8 designer was disabled by BIRT itself (Bugzilla 421879), so this plugin does not have it either.
Use the preview menu above instead.

## Editions (Releases)

| Edition | File | Size | Data sources | Output formats |
|---|---|---|---|---|
| Full | `intellij-birt-<version>.zip` | ~145 MB | Same as the official designer — JDBC, Flat File (CSV), Excel, XML, Web Services, MongoDB, Hive, POJO, Scripted, Sample DB (Classic Models) | PDF, HTML, DOC, DOCX, PPT, PPTX, ODT, ODS, PostScript |
| Lite | `intellij-birt-lite-<version>.zip` | ~123 MB | JDBC, POJO, Scripted | Same, except DOCX |

Editors, tool windows, validation and saving are identical in both editions. Install only one of them (they share the plugin ID, so
installing the other one replaces it). If you open a report that uses a full-only data source with the Lite edition, validation reports
that data source as "invalid manifest" and it cannot be previewed.

## Installation

Windows, IntelliJ IDEA 2025.1 or later (verified against 2025.1, 2026.1 and 2026.2)
> Settings > Plugins > ⚙ > Install Plugin from Disk > the downloaded zip (do not unzip) > restart

## Settings (Settings > Tools > BIRT 4.8) — everything works with all fields empty

| Setting | Default |
|---|---|
| Java | Java bundled with IntelliJ |
| Preview allow-list (DB) | Empty (no restriction). If set, only reports connecting to these addresses are run |
| Preview deny-list (DB) | Empty. Reports connecting to these addresses are not previewed, and data set Preview Results is blocked |
| JDBC driver jars | Empty. Drivers in the report project's `WEB-INF/lib` are used |
| (Optional) Eclipse | Empty |

Connection settings come from the report file. As in the Eclipse BIRT designer, JDBC drivers are taken from `WEB-INF/lib` of the project
containing the report (the Web App Libraries of a web project). **The plugin does not include any database drivers.** Drivers are picked
from `WEB-INF/lib` by file name (`jdbc`, `mysql`, `mariadb`, `postgresql`, `mssql`, `db2jcc`, `jtds`, `tibero`, `altibase`, `cubrid`, `ngdbc`);
add other drivers in Settings or with the designer's Manage Drivers dialog.

The allow/deny lists can also be put in a `.intellij-birt.properties` file in a folder above the report (the first one found walking up
from the report; merged with the IDE settings). Commit this file to share team rules, such as blocking the production database, without
everyone configuring their IDE.

```properties
preview.allowHosts=devdb:1521
preview.denyHosts=proddb:1521
```

### What the database guard covers

- Preview (all formats): checked against the report's connection URL (`odaURL`) — every database
- Designer connections (data set Preview Results, column retrieval, Test Connection): only the drivers below, because BIRT looks up
  connection factories by driver class name —
  Oracle, MySQL, MariaDB, PostgreSQL, SQL Server, jTDS, Db2, SAP HANA, Tibero, Altibase, CUBRID
- It is a guard against mistakes based on comparing URL strings, not a security boundary (for example, it cannot stop a report that changes its URL in a script)

### Notes

- Opening a report loads the driver jars from that project's `WEB-INF/lib`, and previewing runs the scripts inside the report (same as Eclipse BIRT).
  Check projects and reports from unknown sources before opening them.
- Working files live only under `intellij-birt/` in the IDE system folder. Server temp files (`tmp/`) are deleted when the server starts and stops;
  preview output (`preview/`) is deleted when the IDE exits.

## How it works

The plugin starts OSGi with the Eclipse bundles in its `designer/` folder in a separate JVM (the designer server, `BirtDesignerServer`), and
re-parents the windows of the BIRT editors and views opened by that server into IntelliJ editor tabs and tool windows (Windows window
re-parenting). Commands go over stdin, one line each; replies come back on stdout as `@@ ` lines. No network port is opened. The server
stops one minute after the last report editor is closed.

| Path | Contents |
|---|---|
| `intellij/` | The IntelliJ plugin — editor tabs and tool windows (`BirtEmbeddedEditor`, `BirtViewWindows`), sync (`BirtDesignerSync`), saving (`BirtSaving`), structure view, validation and preview (`BirtCliRunner`) |
| `render/` | OSGi bundle of the designer server — command handling (`App`), embedding editors and views (`EmbeddedEditors`, `EmbeddedViews`), database guard (`GuardedConnectionFactory`) |
| `render/bundles.txt` | Eclipse bundles included in the plugin (Lite edition) |
| `render/bundles-full.txt` | Additional bundles for the Full edition (data sources, DOCX output) |
| `cli/src` | Validation, parameters and preview (per output format) — compiled into the render bundle and run inside the server |

The plugin zip consists almost entirely of Eclipse BIRT designer bundles.

## Building

- An Eclipse installation with the BIRT 4.8 designer (the bundles are copied from it) and JDK 8 (for `render`, found by the Gradle toolchain).
  The IntelliJ SDK is the lowest supported version (2025.1), downloaded by Gradle
- Copy `local.properties.example` to `local.properties` and set `eclipsePlugins` (required) and `ideaPath` (optional — an installed IDE to add to the compatibility check)
- Run Gradle with the JBR bundled with IntelliJ
- Driver jars placed in the `drivers` folder of Eclipse's JDBC bundle are never copied into the plugin

```
set JAVA_HOME=C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr
gradlew :intellij:buildPlugin                    (Full)
gradlew :intellij:buildPlugin -Pedition=lite     (Lite)
gradlew :intellij:verifyPlugin                   (compatibility check: 2025.1, 2026.1, installed IDE)
```

Output: `intellij/build/distributions/intellij-birt-<version>.zip`, `intellij-birt-lite-<version>.zip`

## License

- Code in this repository: MIT License ([LICENSE](LICENSE)), except `render/java-modern.profile` (EPL-1.0, modified from Eclipse Equinox)
- Bundled third-party components and their licenses: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) (also inside the release zips); EPL-1.0 text: [licenses/EPL-1.0.html](licenses/EPL-1.0.html)
- The Eclipse BIRT 4.8 bundles and their dependencies in the release zips are unmodified originals, distributed under the licenses in each
  bundle's `about.html` (mostly EPL-1.0 and Apache-2.0). BIRT source: https://github.com/eclipse-birt/birt (4.8 release, build v201806261756);
  Eclipse platform and DTP sources are listed in each bundle's `about.html`.
- Eclipse and BIRT are trademarks of the Eclipse Foundation. This project is not affiliated with the Eclipse Foundation.
