# intelliJ-birt

[English](README.md) | [한국어](README.ko.md) | 日本語 | [简体中文](README.zh-CN.md)

IntelliJ IDEA で Eclipse BIRT 4.8 のレポート(`*.rptdesign`)を Eclipse BIRT デザイナーとまったく同じように編集できる **非公式** プラグインです
(プラグイン名: *Report Designer for BIRT*)。Eclipse BIRT 4.8 デザイナー自体をプラグインに同梱しているため、Eclipse・Java・BIRT を
別途インストールする必要はありません(IntelliJ 同梱の Java で動作します)。

> 現在、プラグインのメニューとメッセージは韓国語です。

| 機能 | 内容 |
|---|---|
| エディタータブ | 下部タブ Layout · Master Page · Script · XML Source。Layout と Master Page は Eclipse BIRT のエディターそのもの(パレット、ルーラー、テーブルハンドル、右クリックメニュー、ドラッグでのサイズ変更) |
| ツールウィンドウ | BIRT Property Editor(下)、BIRT Data Explorer(左)、BIRT Outline と BIRT Resource Explorer(右) — Eclipse のビューそのもの。レポートのデザイナータブを開いている間だけ表示 |
| 同期 | デザイナーや Property Editor での変更はすぐに XML Source に反映。XML Source を編集して Layout に戻ると、Eclipse と同じく保存するか確認 |
| 保存 | Eclipse と同じく Ctrl+S のときだけ保存(自動保存なし)。未保存のタブには `*`、閉じるときに保存するか確認 |
| ショートカット | デザイナー内で Ctrl+Z · Y · C · X · V · A · S |
| 構造ビュー・ハイライト | データソース(接続 URL のみ)・データセット・パラメーター・本文、SQL と JavaScript のハイライト |
| 検証・プレビュー | 保存したファイルを BIRT 4.8 エンジンで検証(エラーを該当行に表示)。右クリック > BIRT プレビューで PDF、HTML、その他の形式(DOC、DOCX、PPT、PPTX、ODT、ODS、PostScript) — Eclipse の Run > View Report と同じ形式 |

BIRT 4.8 デザイナーのエディター内 Preview タブは BIRT 自身が無効にしているため(Bugzilla 421879)、このプラグインにもありません。
プレビューは上記のメニューから行います。

## エディション(Releases)

| エディション | ファイル | サイズ | データソース | 出力形式 |
|---|---|---|---|---|
| フル | `intellij-birt-<バージョン>.zip` | 約 145 MB | 公式デザイナーと同じ — JDBC、Flat File(CSV)、Excel、XML、Web Services、MongoDB、Hive、POJO、スクリプト、サンプル DB(Classic Models) | PDF、HTML、DOC、DOCX、PPT、PPTX、ODT、ODS、PostScript |
| ライト | `intellij-birt-lite-<バージョン>.zip` | 約 123 MB | JDBC、POJO、スクリプト | DOCX 以外は同じ |

エディター、ツールウィンドウ、検証、保存は両エディションで同じです。どちらか一方だけをインストールしてください(プラグイン ID が同じなので、
もう一方をインストールすると置き換わります)。フル専用のデータソースを使うレポートをライト版で開くと、検証でそのデータソースが
"invalid manifest" エラーになり、プレビューできません。

## インストール

Windows、IntelliJ IDEA 2025.1 以降(2025.1・2026.1・2026.2 で互換性を検証)
> Settings > Plugins > ⚙ > Install Plugin from Disk > ダウンロードした zip(展開しない) > 再起動

## 設定(Settings > Tools > BIRT 4.8) — すべて空欄のままでも動作します

| 項目 | 既定値 |
|---|---|
| Java | IntelliJ 同梱の Java |
| プレビュー許可 DB | なし(制限なし)。設定すると、そのアドレスに接続するレポートだけを実行 |
| プレビュー拒否 DB | なし。設定すると、そのアドレスに接続するレポートはプレビューせず、データセットの Preview Results もブロック |
| JDBC ドライバー jar | なし。レポートのプロジェクトの `WEB-INF/lib` にあればそれを使用 |
| (任意)Eclipse | なし |

接続情報はレポートファイルに書かれたものを使います。JDBC ドライバーは Eclipse BIRT デザイナーと同じく、レポートを含むプロジェクトの
`WEB-INF/lib`(Web プロジェクトの Web App Libraries)から探します。**プラグインには DB ドライバーは含まれていません。**
`WEB-INF/lib` からはファイル名でドライバーを選ぶため(`jdbc`、`mysql`、`mariadb`、`postgresql`、`mssql`、`db2jcc`、`jtds`、`tibero`、
`altibase`、`cubrid`、`ngdbc`)、それ以外のドライバーは設定またはデザイナーの Manage Drivers で追加してください。

許可・拒否リストは、レポートより上のフォルダーに置いた `.intellij-birt.properties` でも指定できます(レポートから上へたどって
最初に見つかったファイル。IDE の設定とマージされます)。本番 DB のブロックなどチームで守るルールは、このファイルをリポジトリに
コミットしておけば各自が IDE を設定する必要はありません。

```properties
preview.allowHosts=devdb:1521
preview.denyHosts=proddb:1521
```

### DB ガードの対象範囲

- プレビュー(全出力形式): レポートの接続 URL(`odaURL`)で判定 — すべての DB
- デザイナーでの接続(データセットの Preview Results、列の取得、Test Connection): 以下のドライバーのみ
  (BIRT は接続ファクトリーをドライバークラス名で探すため) —
  Oracle、MySQL、MariaDB、PostgreSQL、SQL Server、jTDS、Db2、SAP HANA、Tibero、Altibase、CUBRID
- 接続 URL の文字列比較によるミス防止の仕組みであり、セキュリティ境界ではありません(スクリプトで URL を変更するレポートなどは防げません)

### 注意

- レポートを開くと、そのプロジェクトの `WEB-INF/lib` のドライバー jar を読み込み、プレビューではレポート内のスクリプトを実行します(Eclipse BIRT と同じ)。
  出所の分からないプロジェクトやレポートは、内容を確認してから開いてください。
- 作業ファイルは IDE のシステムフォルダー内の `intellij-birt/` の下にだけ置きます。サーバーの一時ファイル(`tmp/`)はサーバーの起動時と停止時に、
  プレビュー結果(`preview/`)は IDE の終了時に削除します。

## 仕組み

プラグインは `designer/` フォルダーの Eclipse バンドルで OSGi を別の JVM で起動しておき(デザイナーサーバー、`BirtDesignerServer`)、
そのサーバーが開いた BIRT エディターやビューのウィンドウを IntelliJ のエディタータブやツールウィンドウの中に埋め込みます
(Windows のウィンドウの親の付け替え)。コマンドは標準入力に 1 行ずつ、応答は標準出力の `@@ ` 行で返ります。ネットワークポートは開きません。
最後のレポートエディターを閉じてから 1 分後にサーバーは停止します。

| パス | 内容 |
|---|---|
| `intellij/` | IntelliJ プラグイン — エディタータブとツールウィンドウ(`BirtEmbeddedEditor`、`BirtViewWindows`)、同期(`BirtDesignerSync`)、保存(`BirtSaving`)、構造ビュー、検証とプレビュー(`BirtCliRunner`) |
| `render/` | デザイナーサーバーの OSGi バンドル — コマンド処理(`App`)、エディターとビューの埋め込み(`EmbeddedEditors`、`EmbeddedViews`)、DB ガード(`GuardedConnectionFactory`) |
| `render/bundles.txt` | プラグインに含める Eclipse バンドルの一覧(ライト版) |
| `render/bundles-full.txt` | フル版で追加するバンドル(データソース、DOCX 出力) |
| `cli/src` | 検証、パラメーター、プレビュー(出力形式別) — render バンドルに一緒にコンパイルされ、サーバー内で実行 |

プラグインの zip は、ほぼすべてが Eclipse BIRT デザイナーのバンドルです。

## ビルド

- BIRT 4.8 デザイナーがインストールされた Eclipse(バンドルをそこからコピー)と JDK 8(`render` 用、Gradle toolchain が検出)。
  IntelliJ SDK はサポート範囲の最小バージョン(2025.1)を Gradle がダウンロードして使用
- `local.properties.example` を `local.properties` にコピーし、`eclipsePlugins`(必須)と `ideaPath`(任意 — 互換性チェックに加えるインストール済み IDE)を設定
- Gradle は IntelliJ 同梱の JBR で実行
- Eclipse の JDBC バンドルの `drivers` フォルダーに置いたドライバー jar は、プラグインには含めません

```
set JAVA_HOME=C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\jbr
gradlew :intellij:buildPlugin                    (フル)
gradlew :intellij:buildPlugin -Pedition=lite     (ライト)
gradlew :intellij:verifyPlugin                   (互換性チェック: 2025.1、2026.1、インストール済み IDE)
```

出力: `intellij/build/distributions/intellij-birt-<バージョン>.zip`、`intellij-birt-lite-<バージョン>.zip`

## ライセンス

- このリポジトリのコード: MIT ライセンス([LICENSE](LICENSE))。ただし `render/java-modern.profile` は Eclipse Equinox のファイルを改変したものなので EPL-1.0
- 同梱しているサードパーティ製コンポーネントとライセンス: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)(リリース zip にも同梱)、EPL-1.0 の全文: [licenses/EPL-1.0.html](licenses/EPL-1.0.html)
- リリース zip に含まれる Eclipse BIRT 4.8 のバンドルとその依存バンドルは無改変のオリジナルで、各バンドルの `about.html` に記載された
  ライセンス(主に EPL-1.0、Apache-2.0)に従います。BIRT のソース: https://github.com/eclipse-birt/birt (4.8 リリース、ビルド v201806261756)。
  Eclipse プラットフォームと DTP のソースは各バンドルの `about.html` に記載されています。
- Eclipse と BIRT は Eclipse Foundation の商標です。このプロジェクトは Eclipse Foundation とは関係ありません。
