package com.miniworld.data

import android.content.Context
import com.miniworld.model.LayoutPreset
import com.miniworld.model.Scene
import com.miniworld.model.Slot
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 场景持久化。
 *
 * 用 SharedPreferences + org.json 而不是 Room/DataStore：数据结构很小（一个场景 × 一个槽位），
 * 引入数据库只增加构建体积和依赖风险，换不来任何东西。
 */
class SceneStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadScenes(): List<Scene> {
        val raw = prefs.getString(KEY_SCENES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { parseScene(it) }
            }
        }.getOrElse { emptyList() }
    }

    fun saveScenes(scenes: List<Scene>) {
        val array = JSONArray()
        scenes.forEach { array.put(sceneToJson(it)) }
        prefs.edit().putString(KEY_SCENES, array.toString()).apply()
    }

    fun upsert(scene: Scene) {
        val scenes = loadScenes().toMutableList()
        val index = scenes.indexOfFirst { it.id == scene.id }
        if (index >= 0) scenes[index] = scene else scenes += scene
        saveScenes(scenes)
    }

    fun delete(id: String) {
        saveScenes(loadScenes().filterNot { it.id == id })
        if (activeSceneId() == id) setActiveScene(null)
    }

    fun newScene(name: String = "新场景"): Scene = Scene(
        id = UUID.randomUUID().toString(),
        name = name,
        // 默认「一个全屏 + 其余隐形保活」：用户明确要求"小窗别出现、保活在后台、
        // 用按钮切回前台"。其余窗口缩到 2×2 像素，进程照样活着，但看不见。
        preset = LayoutPreset.SOLO_KEEPALIVE,
        slots = emptyList()
    )

    fun activeSceneId(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActiveScene(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id)
        }.apply()
    }

    fun activeScene(): Scene? {
        val id = activeSceneId() ?: return null
        return loadScenes().firstOrNull { it.id == id }
    }

    /**
     * 记住"上次哪条启动策略成功了"。
     * 下次直接用它，省掉盲试带来的黑屏闪烁。
     */
    fun workingStrategy(): String? = prefs.getString(KEY_STRATEGY, null)

    fun setWorkingStrategy(strategy: String?) {
        prefs.edit().apply {
            if (strategy == null) remove(KEY_STRATEGY) else putString(KEY_STRATEGY, strategy)
        }.apply()
    }

    // ------------------------------------------------------------------ JSON

    private fun sceneToJson(scene: Scene): JSONObject = JSONObject().apply {
        put("id", scene.id)
        put("name", scene.name)
        put("preset", scene.preset.name)
        put("slots", JSONArray().apply {
            scene.slots.forEach { slot ->
                put(JSONObject().apply {
                    put("index", slot.index)
                    put("packageName", slot.packageName)
                    put("component", slot.component ?: JSONObject.NULL)
                    put("label", slot.label)
                    // 不写进去的话，重启 App 后分身那份（user 999）会退化成主系统那份
                    put("userId", slot.userId)
                })
            }
        })
    }

    private fun parseScene(json: JSONObject): Scene {
        val slotsArray = json.optJSONArray("slots") ?: JSONArray()
        val slots = (0 until slotsArray.length()).mapNotNull { i ->
            slotsArray.optJSONObject(i)?.let { s ->
                Slot(
                    index = s.optInt("index", i),
                    packageName = s.optString("packageName"),
                    component = s.optString("component").takeIf { it.isNotBlank() && it != "null" },
                    label = s.optString("label", s.optString("packageName")),
                    userId = s.optInt("userId", 0)
                )
            }
        }

        return Scene(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "未命名场景"),
            preset = LayoutPreset.fromName(json.optString("preset")),
            slots = slots
        )
    }

    private companion object {
        const val PREFS = "onestep_scenes"
        const val KEY_SCENES = "scenes"
        const val KEY_ACTIVE = "active_scene"
        const val KEY_STRATEGY = "working_strategy"
    }
}
