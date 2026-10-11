package com.kongbai.modmigrator

/**
 * 数据包生成器清单。
 *
 * 由 schema 推导生成，不要手改 —— mcdoc 更新后重新生成即可。
 *
 * kind 决定用哪套表单：
 *   SCHEMA：按 mcdoc 类型动态生成（绝大多数）
 *   TAG   ：标签，结构固定
 *   FIXED ：pack.mcmeta，结构固定
 *   TEXT  ：自由 JSON（文本组件这类没有 schema 的）
 *   NONE  ：schema 里暂时找不到根类型，先禁用
 */
object DatapackCatalog {

    enum class Kind { SCHEMA, TAG, FIXED, TEXT, NONE }

    class G(
        val id: String,
        val title: String,
        /** mcdoc 根类型路径；非 SCHEMA 时为 null */
        val path: String?,
        /** data/<命名空间>/<dir>/ 下的子目录；null 表示不在 data 下 */
        val dir: String?,
        val kind: Kind
    )

    val ALL: List<G> = listOf(
        G("advancement", "进度", "::java::data::advancement::Advancement", "advancements", Kind.SCHEMA),
        G("atlas", "图集", "::java::assets::atlas::Atlas", "atlases", Kind.SCHEMA),
        G("banner_pattern", "旗帜图案", "::java::data::variants::banner_pattern::BannerPattern", "banner_patterns", Kind.SCHEMA),
        G("block_definition", "方块状态", null, "block_definitions", Kind.NONE),
        G("block_transformer", "方块转换器", "::java::data::block_transformer::BlockTransformData", "block_transformers", Kind.SCHEMA),
        G("cat_sound_variant", "猫叫声变体", "::java::data::variants::cat::CatSounds", "cat_sound_variants", Kind.SCHEMA),
        G("cat_variant", "猫变种", "::java::data::variants::cat::CatVariant", "cat_variants", Kind.SCHEMA),
        G("chat_type", "聊天类型", "::java::data::chat_type::ChatType", "chat_types", Kind.SCHEMA),
        G("chicken_sound_variant", "鸡叫声变体", "::java::data::variants::chicken::ChickenSounds", "chicken_sound_variants", Kind.SCHEMA),
        G("chicken_variant", "鸡变种", "::java::data::variants::chicken::ChickenVariant", "chicken_variants", Kind.SCHEMA),
        G("context_float_provider", "上下文浮点提供器", "::java::data::number_provider::context_float::ContextFloatProvider", "context_float_providers", Kind.SCHEMA),
        G("context_int_provider", "上下文整数提供器", "::java::data::number_provider::context_int::ContextIntProvider", "context_int_providers", Kind.SCHEMA),
        G("cow_sound_variant", "牛叫声变体", "::java::data::variants::cow::CowSounds", "cow_sound_variants", Kind.SCHEMA),
        G("cow_variant", "牛变种", "::java::data::variants::cow::CowVariant", "cow_variants", Kind.SCHEMA),
        G("damage_type", "伤害类型", "::java::data::damage_type::DamageType", "damage_types", Kind.SCHEMA),
        G("decorated_pot_pattern", "饰纹陶罐图案", "::java::data::decorated_pot_pattern::DecoratedPotPattern", "decorated_pot_patterns", Kind.SCHEMA),
        G("dialog", "对话框", "::java::data::dialog::Dialog", "dialogs", Kind.SCHEMA),
        G("dimension", "维度", "::java::data::worldgen::dimension::Dimension", "dimension", Kind.SCHEMA),
        G("dimension_type", "维度类型", "::java::data::worldgen::dimension::DimensionType", "dimension_types", Kind.SCHEMA),
        G("enchantment", "魔咒", "::java::data::enchantment::Enchantment", "enchantments", Kind.SCHEMA),
        G("enchantment_provider", "魔咒提供器", "::java::data::enchantment::provider::EnchantmentProvider", "enchantment_providers", Kind.SCHEMA),
        G("equipment", "装备", "::java::assets::equipment::Equipment", "equipments", Kind.SCHEMA),
        G("font", "字体", "::java::assets::font::Font", "fonts", Kind.SCHEMA),
        G("frog_variant", "青蛙变种", "::java::data::variants::frog::FrogVariant", "frog_variants", Kind.SCHEMA),
        G("instrument", "乐器", "::java::data::variants::instrument::Instrument", "instruments", Kind.SCHEMA),
        G("item_definition", "物品", "::java::assets::item_definition::ItemDefinition", "item_definitions", Kind.SCHEMA),
        G("item_modifier", "物品修饰器", "::java::data::item_modifier::ItemModifier", "item_modifiers", Kind.SCHEMA),
        G("jukebox_song", "唱片机曲目", "::java::data::variants::jukebox_song::JukeboxSong", "jukebox_songs", Kind.SCHEMA),
        G("lang", "语言", "::java::assets::lang::Lang", "langs", Kind.SCHEMA),
        G("loot_table", "战利品表", "::java::data::loot::LootTable", "loot_tables", Kind.SCHEMA),
        G("model", "模型", "::java::assets::item_definition::Model", "models", Kind.SCHEMA),
        G("pack_mcmeta", "pack.mcmeta", null, null, Kind.FIXED),
        G("painting_variant", "画变种", "::java::data::variants::painting::PaintingVariant", "painting_variants", Kind.SCHEMA),
        G("pig_sound_variant", "猪叫声变体", "::java::data::variants::pig::PigSounds", "pig_sound_variants", Kind.SCHEMA),
        G("pig_variant", "猪变种", "::java::data::variants::pig::PigVariant", "pig_variants", Kind.SCHEMA),
        G("post_effect", "后处理管线程序文件", "::java::assets::shader::post::PostEffect", "post_effects", Kind.SCHEMA),
        G("predicate", "谓词", "::java::data::predicate::Predicate", "predicates", Kind.SCHEMA),
        G("recipe", "配方", "::java::data::recipe::Recipe", "recipes", Kind.SCHEMA),
        G("slot_source", "槽位来源", "::java::data::slot_source::SlotSource", "slot_sources", Kind.SCHEMA),
        G("sounds", "声音事件", "::java::assets::sounds::Sounds", "sounds", Kind.SCHEMA),
        G("sulfur_cube_archetype", "硫方块原型", "::java::data::sulfur_cube_archetype::SulfurCubeArchetype", "sulfur_cube_archetypes", Kind.SCHEMA),
        G("tag/banner_pattern", "旗帜图案标签", null, "tags/banner_pattern", Kind.TAG),
        G("tag/block", "方块标签", null, "tags/block", Kind.TAG),
        G("tag/cat_variant", "猫变种标签", null, "tags/cat_variant", Kind.TAG),
        G("tag/damage_type", "伤害类型标签", null, "tags/damage_type", Kind.TAG),
        G("tag/dialog", "对话框标签", null, "tags/dialog", Kind.TAG),
        G("tag/enchantment", "魔咒标签", null, "tags/enchantment", Kind.TAG),
        G("tag/entity_type", "实体类型标签", null, "tags/entity_type", Kind.TAG),
        G("tag/fluid", "流体标签", null, "tags/fluid", Kind.TAG),
        G("tag/game_event", "游戏事件标签", null, "tags/game_event", Kind.TAG),
        G("tag/instrument", "乐器标签", null, "tags/instrument", Kind.TAG),
        G("tag/item", "物品标签", null, "tags/item", Kind.TAG),
        G("tag/painting_variant", "画变种标签", null, "tags/painting_variant", Kind.TAG),
        G("tag/point_of_interest_type", "兴趣点类型标签", null, "tags/point_of_interest_type", Kind.TAG),
        G("tag/timeline", "时间线标签", null, "tags/timeline", Kind.TAG),
        G("tag/trade_set", "标签 · 交易组", null, "tags/trade_set", Kind.TAG),
        G("tag/villager_trade", "标签 · 村民交易", null, "tags/villager_trade", Kind.TAG),
        G("tag/worldgen/biome", "生物群系标签", null, "tags/worldgen/biome", Kind.TAG),
        G("tag/worldgen/flat_level_generator_preset", "超平坦预设标签", null, "tags/worldgen/flat_level_generator_preset", Kind.TAG),
        G("tag/worldgen/structure", "结构标签", null, "tags/worldgen/structure", Kind.TAG),
        G("tag/worldgen/structure_set", "结构集标签", null, "tags/worldgen/structure_set", Kind.TAG),
        G("tag/worldgen/world_preset", "世界预设标签", null, "tags/worldgen/world_preset", Kind.TAG),
        G("tag/zombie_nautilus_variant", "僵尸鹦鹉螺标签", null, "tags/zombie_nautilus_variant", Kind.TAG),
        G("test_environment", "测试环境", "::java::data::gametest::test_environment::TestEnvironment", "test_environments", Kind.SCHEMA),
        G("test_instance", "测试实例", "::java::data::gametest::TestInstance", "test_instances", Kind.SCHEMA),
        G("text_component", "文本组件", null, null, Kind.TEXT),
        G("texture_meta", "纹理元数据", "::java::assets::texture_meta::TextureMeta", "texture_metas", Kind.SCHEMA),
        G("timeline", "时间线", "::java::data::timeline::Timeline", "timelines", Kind.SCHEMA),
        G("trade_set", "交易组", "::java::data::trade_set::TradeSet", "trade_sets", Kind.SCHEMA),
        G("trial_spawner", "试炼刷怪笼", "::java::world::block::spawner::TrialSpawner", "trial_spawners", Kind.SCHEMA),
        G("trim_material", "盔甲纹饰材料", "::java::data::trim::TrimMaterial", "trim_materials", Kind.SCHEMA),
        G("trim_pattern", "盔甲纹饰图案", "::java::data::trim::TrimPattern", "trim_patterns", Kind.SCHEMA),
        G("villager_trade", "村民交易", "::java::data::villager_trade::VillagerTrade", "villager_trades", Kind.SCHEMA),
        G("wolf_sound_variant", "狼音效变种", "::java::data::variants::wolf::WolfSounds", "wolf_sound_variants", Kind.SCHEMA),
        G("wolf_variant", "狼变种", "::java::data::variants::wolf::WolfVariant", "wolf_variants", Kind.SCHEMA),
        G("world", "世界设置", null, "worlds", Kind.NONE),
        G("world_clock", "世界时钟", "::java::data::world_clock::<anonymous 0>", "world_clocks", Kind.SCHEMA),
        G("worldgen/biome", "生物群系", "::java::data::worldgen::biome::Biome", "worldgen/biome", Kind.SCHEMA),
        G("worldgen/carver", "世界生成 · 洞穴雕刻器", "::java::data::worldgen::carver::CarverConfigBase", "worldgen/carver", Kind.SCHEMA),
        G("worldgen/configured_carver", "已配置的雕刻器", "::java::data::worldgen::carver::ConfiguredCarver", "worldgen/configured_carver", Kind.SCHEMA),
        G("worldgen/configured_feature", "已配置的地物", "::java::data::worldgen::feature::ConfiguredFeature", "worldgen/configured_feature", Kind.SCHEMA),
        G("worldgen/configured_structure_feature", "结构地物", "::java::data::worldgen::structure::Structure", "worldgen/configured_structure_feature", Kind.SCHEMA),
        G("worldgen/configured_surface_builder", "地表生成器", "::java::data::worldgen::surface_builder::ConfiguredSurfaceBuilder", "worldgen/configured_surface_builder", Kind.SCHEMA),
        G("worldgen/density_function", "密度函数", "::java::data::worldgen::density_function::DensityFunction", "worldgen/density_function", Kind.SCHEMA),
        G("worldgen/feature", "世界生成 · 地物", null, "worldgen/feature", Kind.NONE),
        G("worldgen/flat_level_generator_preset", "超平坦世界预设", "::java::data::worldgen::world_preset::FlatGeneratorPreset", "worldgen/flat_level_generator_preset", Kind.SCHEMA),
        G("worldgen/material_condition", "世界生成 · 材质条件", "::java::data::worldgen::material_condition::MaterialCondition", "worldgen/material_condition", Kind.SCHEMA),
        G("worldgen/material_rule", "世界生成 · 材质规则", "::java::data::worldgen::material_rule::MaterialRule", "worldgen/material_rule", Kind.SCHEMA),
        G("worldgen/noise", "噪声", "::java::data::worldgen::density_function::Noise", "worldgen/noise", Kind.SCHEMA),
        G("worldgen/noise_settings", "噪声设置", "::java::data::worldgen::noise_settings::NoiseGeneratorSettings", "worldgen/noise_settings", Kind.SCHEMA),
        G("worldgen/placed_feature", "已放置的地物", "::java::data::worldgen::feature::placement::PlacedFeature", "worldgen/placed_feature", Kind.SCHEMA),
        G("worldgen/processor_list", "处理器列表", "::java::data::worldgen::processor_list::ProcessorList", "worldgen/processor_list", Kind.SCHEMA),
        G("worldgen/structure", "结构", "::java::data::worldgen::structure::Structure", "worldgen/structure", Kind.SCHEMA),
        G("worldgen/structure_set", "结构集", "::java::data::worldgen::structure_set::StructureSet", "worldgen/structure_set", Kind.SCHEMA),
        G("worldgen/template_pool", "模板池", "::java::data::worldgen::template_pool::TemplatePool", "worldgen/template_pool", Kind.SCHEMA),
        G("worldgen/world_preset", "世界预设", "::java::data::worldgen::world_preset::WorldPreset", "worldgen/world_preset", Kind.SCHEMA),
        G("zombie_nautilus_variant", "僵尸鹦鹉螺", "::java::data::variants::zombie_nautilus::ZombieNautilusVariant", "zombie_nautilus_variants", Kind.SCHEMA),
    )

    fun byId(id: String): G? = ALL.firstOrNull { it.id == id }

    /** 标签的注册表类型，就是 id 去掉 tag/ 前缀 */
    fun tagRegistry(g: G): String = g.id.removePrefix("tag/")
}
