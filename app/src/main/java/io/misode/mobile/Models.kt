package io.misode.mobile

/**
 * A single entry of misode's generator catalogue (137 entries in the current
 * upstream config).
 */
data class GeneratorInfo(
	/** Stable id, e.g. `loot_table`. */
	val id: String,
	/** Route segment used by the web app, e.g. `loot-table`. */
	val url: String,
	/** Human readable title (English locale). */
	val title: String,
	/** Route the host app can pass to [MisodeView.navigate]. */
	val path: String,
	val category: String? = null,
	val minVersion: String? = null,
	val maxVersion: String? = null,
	val wiki: String? = null,
)

/** A language selectable through [MisodeView.setLanguage]. */
data class LanguageInfo(val code: String, val name: String)

/** Events pushed from the page to [MisodeView.addListener]. */
enum class MisodeEvent(val jsName: String) {
	READY("ready"),
	ROUTE("route"),
	OUTPUT("output"),
	THEME("theme"),
	VERSION("version"),
	TITLE("title"),
	STORE("store"),
	;

	internal companion object {
		fun fromJs(name: String) = entries.firstOrNull { it.jsName == name }
	}
}

/** Payload delivered with an event. */
data class MisodeEventData(
	val type: MisodeEvent,
	/** String payload for `route` / `output` / `title` / `version` / `theme`. */
	val value: String? = null,
	/** Raw JSON for `ready` / `store`. */
	val json: String? = null,
)

/** Read-only snapshot of the settings currently stored by the page. */
data class MisodeSettings(
	val version: String,
	val theme: String,
	val language: String,
	val format: String,
	val indent: String,
	val highlighting: Boolean,
	val soundsVersion: String,
	val treeViewMode: String,
	val openProject: String,
)
