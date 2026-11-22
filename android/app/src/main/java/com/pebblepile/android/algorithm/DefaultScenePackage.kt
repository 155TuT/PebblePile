package com.pebblepile.android.algorithm

/** 离线随包的默认饮食场景包；换词表或菜单时递增版本并写入清单。 */
object DefaultDietScenePackage {
    const val SCENE_ID = "diet"
    const val VERSION = "diet-scene-pack-0.1.0"

    val menu = listOf(
        MenuItem("noodle-soup", "热汤面", priceCny = 18, prepMinutes = 10, cleanupNeeded = false, convenienceScore = 9, matchedMeal = "热汤面"),
        MenuItem("rice-bowl", "盖饭", priceCny = 16, prepMinutes = 10, cleanupNeeded = false, convenienceScore = 9, matchedMeal = "盖饭"),
        MenuItem("bento", "轻食便当", priceCny = 19, prepMinutes = 5, cleanupNeeded = false, convenienceScore = 8),
        MenuItem("stirfry", "家常炒菜", priceCny = 15, prepMinutes = 35, cleanupNeeded = true, convenienceScore = 3),
        MenuItem("tomato-egg-noodle", "番茄鸡蛋面", priceCny = 20, prepMinutes = 40, cleanupNeeded = true, convenienceScore = 2, matchedMeal = "番茄鸡蛋面"),
        MenuItem("hotpot", "麻辣烫", priceCny = 22, prepMinutes = 15, cleanupNeeded = true, convenienceScore = 5),
    )

    val labels = listOf(
        VocabEntry("饮食", listOf("吃", "饭", "汤", "晚餐", "面", "盖饭", "番茄鸡蛋面", "麻辣烫"), "scenario"),
        VocabEntry("加班", listOf("加班", "工作", "忙"), "context"),
        VocabEntry("省事", listOf("省事", "省时", "不用洗锅", "不用准备食材", "十分钟就吃上了"), "preference"),
        VocabEntry("周末", listOf("周日", "周末", "周六"), "context"),
        VocabEntry("慢做饭", listOf("慢慢做饭", "愿意花四十分钟", "洗锅"), "preference"),
    )

    val scenePackage = ScenePackage(
        sceneId = SCENE_ID,
        version = VERSION,
        labels = labels,
        menu = menu,
        minIndependentSupports = 2,
    )
}
