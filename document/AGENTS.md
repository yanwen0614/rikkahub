# document 模块

## 模块定位
文档解析模块，将多种文档格式转为 Markdown/纯文本，供上层 AI 对话引用。
Android Library（`rikkahub.android.library`），包名 `me.rerere.document`。
零第三方重型依赖：Office/EPUB 均用 `ZipFile/ZipInputStream + XmlPullParser` 手写解析。

## 解析入口（`me.rerere.document`，入参均为 `java.io.File`，返回 `String`）
- PDF → `PdfParser.parserPdf(file)`（注意拼写即如此），基于 MuPDF `PDFDocument`。
- DOCX → `DocxParser.parse(file)`，解 `word/document.xml`，段落/标题/列表/表格转 Markdown。
- PPTX → `PptxParser.parse(file)`，遍历 `ppt/slides/slideN.xml` + 备注 `ppt/notesSlides/`。
- EPUB → `EpubParser.parse(file)`，`META-INF/container.xml` → OPF manifest/spine → XHTML 转文本。
- 失败不抛异常：返回 `"Error parsing ..."` / `"No ... found"` 提示串，上层直接展示即可。

## 大测试资产警告
- 模块内测试资产约 19M，多为 PDF/DOCX/PPTX/EPUB 样例文件，仅供本地解析验证。
- 勿提交大文件到 git；不要随意复制测试资产到其他目录或打包进 APK/assets。
- 新增测试请复用现有样例，避免引入新的大文件；确需添加先检查体积。

## 构建/测试注意
- 构建：`:document:assembleRelease`；改动后跑 `./gradlew :document:lint`。
- 测试：`./gradlew :document:test`（JVM 单测），instrumented 测试需真机/模拟器。
- MuPDF 为 PDF 唯一原生依赖，升级需同步验证 `parserPdf` 分页文本输出。
- 不要动其他模块：解析结果的消费方在 `app`（transformer），本模块只做纯解析。
