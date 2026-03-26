package org.skepsun.kototoro.parsers.ksp

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
import java.io.File
import java.io.Writer
import java.util.*

class ParserProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {
    private val availableLocales = Locale.getAvailableLocales().toSet()
    private val sourceNamePattern = Regex("[A-Z_][A-Z0-9_]{3,}")

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation("org.skepsun.kototoro.parsers.ContentSourceParser")
        val ret = symbols.filterNot { it.validate() }.toList()
        if (!symbols.iterator().hasNext()) {
            return ret
        }
        val dependencies = Dependencies.ALL_FILES
        val factoryFile =
            try {
                codeGenerator.createNewFile(
                    dependencies = dependencies,
                    packageName = "org.skepsun.kototoro.parsers",
                    fileName = "ContentParserFactory",
                )
            } catch (e: FileAlreadyExistsException) {
                logger.warn(e.toString(), null)
                null
            }
        val sourcesFile =
            try {
                codeGenerator.createNewFile(
                    dependencies = dependencies,
                    packageName = "org.skepsun.kototoro.parsers.model",
                    fileName = "ContentSource",
                )
            } catch (e: FileAlreadyExistsException) {
                logger.warn(e.toString(), null)
                null
            }
        val totalCount = sourcesFile?.writer().use { sourcesWriter ->
            factoryFile?.writer().use { factoryWriter ->
                writeContent(sourcesWriter, factoryWriter, symbols)
            }
        }
        writeSummary(totalCount)
        return ret
    }

    private fun writeContent(
        sourcesWriter: Writer?,
        factoryWriter: Writer?,
        symbols: Sequence<KSAnnotated>,
    ): Int {
        if (sourcesWriter == null && factoryWriter == null) {
            return 0
        }
        factoryWriter?.write(
            """
			package org.skepsun.kototoro.parsers

			import org.skepsun.kototoro.parsers.model.ContentParserSource
			import org.skepsun.kototoro.parsers.core.ContentParserWrapper

			internal fun ContentParserSource.newParser(context: ContentLoaderContext): ContentParser = when (this) {

			""".trimIndent(),
        )
        sourcesWriter?.write(
            """
			package org.skepsun.kototoro.parsers.model

			public enum class ContentParserSource(
				public val title: String,
				override val locale: String,
				override val contentType: ContentType,
				public val isBroken: Boolean,
			): ContentSource {

			""".trimIndent(),
        )

        val visitor = ParserVisitor(sourcesWriter, factoryWriter)
        val totalCount = symbols
            .filter { it is KSClassDeclaration && it.validate() }
            .onEach { it.accept(visitor, Unit) }
            .count()

        factoryWriter?.write(
            """
			}.let {
				require(it.source == this) {
					"Cannot instantiate content parser: ${'$'}name mapped to ${'$'}{it.source}"
				}
				ContentParserWrapper(it)
			}
			""".trimIndent(),
        )
        sourcesWriter?.write(
            """
				;
			}
			""".trimIndent(),
        )
        return totalCount
    }

    private fun writeSummary(totalCount: Int) {
        val file = File(options["summaryOutputDir"] ?: return, "summary.yaml")
        file.writeText("total: $totalCount")
    }

    private inner class ParserVisitor(
        private val sourcesWriter: Writer?,
        private val factoryWriter: Writer?,
    ) : KSVisitorVoid() {
        private val titles = HashMap<String, String>()

        override fun visitClassDeclaration(
            classDeclaration: KSClassDeclaration,
            data: Unit,
        ) {
            if (classDeclaration.classKind != ClassKind.CLASS || classDeclaration.isAbstract()) {
                logger.error("Only non-abstract can be annotated with @ContentSourceParser", classDeclaration)
            }
            val annotation = classDeclaration.annotations.single { it.shortName.asString() == "ContentSourceParser" }
            val deprecation = classDeclaration.annotations.singleOrNull { it.shortName.asString() == "Deprecated" }
            val isBroken = classDeclaration.annotations.any { it.shortName.asString() == "Broken" }
            val args = annotation.arguments.associateBy { it.name?.asString() }
            val name = args["name"]?.value as? String
            val title = args["title"]?.value as? String
            val locale = args["locale"]?.value as? String ?: ""
            val type = args["type"]?.value ?: "ContentType.MANGA"
            if (name.isNullOrBlank() || title.isNullOrBlank()) {
                logger.error("@ContentSourceParser missing required name/title", classDeclaration)
                return
            }
            val typeString = when (val t = type) {
                is String -> t
                else -> t.toString()
            }
            val localeString = "\"$locale\""
            val localeObj = if (locale.isEmpty()) null else Locale(locale)
            val localeTitle = localeObj?.getDisplayLanguage(localeObj)
            if (localeObj != null && localeObj !in availableLocales) {
                logger.error("Content source $name has wrong locale: $localeTitle")
            }

            if (!sourceNamePattern.matches(name)) {
                logger.error("Content source name must be uppercase: $name")
            }

            val constructor = classDeclaration.primaryConstructor
            if (constructor == null || constructor.parameters.count { !it.hasDefault } != 1) {
                logger.error(
                    "Class with @ContentSourceParser must have a primary constructor with one parameter",
                    classDeclaration,
                )
            }
            val className = checkNotNull(classDeclaration.qualifiedName?.asString()) { "Class name is null" }

            val prevTitleClass = titles.put(title, className)
            if (prevTitleClass != null) {
                logger.warn("Source title duplication: \"$title\" is assigned to both $prevTitleClass and $className")
            }

            factoryWriter?.write("\tContentParserSource.$name -> $className(context)\n")
            val deprecationString =
                if (deprecation != null) {
                    val reason =
                        deprecation.arguments
                            .find { it.name?.asString() == "message" }
                            ?.value
                            ?.toString() ?: "Unknown reason"
                    "@Deprecated(\"$reason\") "
                } else {
                    ""
                }
            val localeComment = localeTitle?.toTitleCase(localeObj)?.let { " /* $it */" }.orEmpty()
            sourcesWriter?.write(
                "\t$deprecationString$name(\"$title\", $localeString$localeComment, $typeString, $isBroken),\n",
            )
        }
    }
}
