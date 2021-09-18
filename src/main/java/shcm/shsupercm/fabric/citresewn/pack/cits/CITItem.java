package shcm.shsupercm.fabric.citresewn.pack.cits;

import com.google.common.collect.ImmutableMap;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.JsonUnbakedModel;
import net.minecraft.client.render.model.json.ModelOverride;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import org.apache.commons.io.IOUtils;
import shcm.shsupercm.fabric.citresewn.CITResewn;
import shcm.shsupercm.fabric.citresewn.ex.CITLoadException;
import shcm.shsupercm.fabric.citresewn.ex.CITParseException;
import shcm.shsupercm.fabric.citresewn.mixin.cititem.JsonUnbakedModelAccessor;
import shcm.shsupercm.fabric.citresewn.pack.CITPack;
import shcm.shsupercm.fabric.citresewn.pack.ResewnItemModelIdentifier;
import shcm.shsupercm.fabric.citresewn.pack.ResewnTextureIdentifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class CITItem extends CIT {
    public Map<Identifier, Identifier> assetIdentifiers = new LinkedHashMap<>();
    public Map<List<ModelOverride.Condition>, JsonUnbakedModel> unbakedAssets = new LinkedHashMap<>();
    private boolean isTexture = false;

    public BakedModel bakedModel = null;
    public CITOverrideList bakedSubModels = new CITOverrideList();

    public CITItem(CITPack pack, Identifier identifier, Properties properties) throws CITParseException {
        super(pack, identifier, properties);
        try {
            if (this.items.size() == 0)
                throw new Exception("CIT must target at least one item type");

            String modelProp = properties.getProperty("model");
            Identifier assetIdentifier = resolvePath(identifier, modelProp, ".json", id -> pack.resourcePack.contains(ResourceType.CLIENT_RESOURCES, id));
            if (assetIdentifier != null)
                assetIdentifiers.put(null, assetIdentifier);
            else if (modelProp != null && !modelProp.startsWith("models")) {
                assetIdentifier = resolvePath(identifier, "models/" + modelProp, ".json", id -> pack.resourcePack.contains(ResourceType.CLIENT_RESOURCES, id));
                if (assetIdentifier != null)
                    assetIdentifiers.put(null, assetIdentifier);
            }

            for (Object o : properties.keySet())
                if (o instanceof String property && property.startsWith("model.")) {
                    Identifier subIdentifier = resolvePath(identifier, properties.getProperty(property), ".json", id -> pack.resourcePack.contains(ResourceType.CLIENT_RESOURCES, id));
                    if (subIdentifier == null)
                        throw new Exception("Cannot resolve path for " + property);

                    String subItem = property.substring(6);
                    Identifier subItemIdentifier = fixDeprecatedSubItem(subItem);
                    assetIdentifiers.put(subItemIdentifier == null ? new Identifier("minecraft", "item/" + subItem) : subItemIdentifier, subIdentifier);
                }

            if (assetIdentifiers.size() == 0) {
                isTexture = true;
                assetIdentifier = resolvePath(identifier, properties.getProperty("texture"), ".png", id -> pack.resourcePack.contains(ResourceType.CLIENT_RESOURCES, id));
                if (assetIdentifier != null)
                    assetIdentifiers.put(null, assetIdentifier);

                for (Object o : properties.keySet())
                    if (o instanceof String property && property.startsWith("texture.")) {
                        Identifier subIdentifier = resolvePath(identifier, properties.getProperty(property), ".png", id -> pack.resourcePack.contains(ResourceType.CLIENT_RESOURCES, id));
                        if (subIdentifier == null)
                            throw new Exception("Cannot resolve path for " + property);

                        String subItem = property.substring(8);
                        Identifier subItemIdentifier = fixDeprecatedSubItem(subItem);
                        assetIdentifiers.put(subItemIdentifier == null ? new Identifier("minecraft", "item/" + subItem) : subItemIdentifier, subIdentifier);
                    }
            }

            if (assetIdentifiers.size() == 0)
                throw new Exception("Cannot resolve path for model/texture");
        } catch (Exception e) {
            throw new CITParseException(pack.resourcePack, identifier, (e.getClass() == Exception.class ? "" : e.getClass().getSimpleName() + ": ") + e.getMessage());
        }
    }

    public void loadUnbakedAssets(ResourceManager resourceManager) throws CITLoadException {
        try {
            if (isTexture) {
                Supplier<JsonUnbakedModel> itemModelGenerator = new Supplier<>() {
                    private final Identifier firstItemIdentifier = Registry.ITEM.getId(items.iterator().next()), firstItemModelIdentifier = new Identifier(firstItemIdentifier.getNamespace(), "models/item/" + firstItemIdentifier.getPath() + ".json");
                    @Override
                    public JsonUnbakedModel get() {
                        Resource itemModelResource = null;
                        try {
                            return JsonUnbakedModel.deserialize(IOUtils.toString((itemModelResource = resourceManager.getResource(firstItemModelIdentifier)).getInputStream(), StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            return null;
                        } finally {
                            IOUtils.closeQuietly(itemModelResource);
                        }
                    }
                };

                JsonUnbakedModel itemJson = itemModelGenerator.get();
                Map<String, Either<SpriteIdentifier, String>> textureOverrideMap = new HashMap<>();
                if (((JsonUnbakedModelAccessor) itemJson).getTextureMap().size() > 1) { // use(some/all of) the asset identifiers to build texture override in layered models
                    textureOverrideMap = ((JsonUnbakedModelAccessor) itemJson).getTextureMap();
                    Identifier defaultAsset = assetIdentifiers.get(null);
                    textureOverrideMap.replaceAll((layerName, originalTextureEither) -> {
                        Identifier textureIdentifier = assetIdentifiers.remove(originalTextureEither.map(SpriteIdentifier::getTextureId, Identifier::new));
                        if (textureIdentifier != null)
                            return Either.left(new SpriteIdentifier(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE, new ResewnTextureIdentifier(textureIdentifier)));
                        if (defaultAsset != null)
                            return Either.left(new SpriteIdentifier(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE, new ResewnTextureIdentifier(defaultAsset)));
                        return null;
                    });

                    if (assetIdentifiers.size() == 0 || (assetIdentifiers.size() == 1 && assetIdentifiers.containsKey(null))) {
                        unbakedAssets.put(null, itemJson);
                        return;
                    }
                }

                Identifier baseIdentifier = assetIdentifiers.remove(null);

                if (baseIdentifier != null)
                    unbakedAssets.put(null, loadUnbakedAsset(resourceManager, textureOverrideMap, itemModelGenerator, baseIdentifier));

                if (!assetIdentifiers.isEmpty()) { // contains sub models
                    LinkedHashMap<Identifier, List<ModelOverride.Condition>> overrideConditions = new LinkedHashMap<>();
                    for (Item item : this.items) {
                        Identifier itemIdentifier = Registry.ITEM.getId(item);
                        overrideConditions.put(new Identifier(itemIdentifier.getNamespace(), "item/" + itemIdentifier.getPath()), Collections.emptyList());

                        Identifier itemModelIdentifier = new Identifier(itemIdentifier.getNamespace(), "models/item/" + itemIdentifier.getPath() + ".json");
                        try (Resource itemModelResource = resourceManager.getResource(itemModelIdentifier); Reader resourceReader = new InputStreamReader(itemModelResource.getInputStream())) {
                            JsonUnbakedModel itemModelJson = JsonUnbakedModel.deserialize(resourceReader);

                            if (itemModelJson.getOverrides() != null && !itemModelJson.getOverrides().isEmpty())
                                for (ModelOverride override : itemModelJson.getOverrides())
                                    overrideConditions.put(override.getModelId(), override.streamConditions().toList());
                        }
                    }

                    ArrayList<Identifier> overrideModels = new ArrayList<>(overrideConditions.keySet());
                    Collections.reverse(overrideModels);

                    for (Identifier overrideModel : overrideModels) {
                        Identifier replacement = assetIdentifiers.remove(overrideModel);
                        if (replacement == null)
                            continue;

                        List<ModelOverride.Condition> conditions = overrideConditions.get(overrideModel);
                        unbakedAssets.put(conditions, loadUnbakedAsset(resourceManager, textureOverrideMap, itemModelGenerator, replacement));
                    }
                }
            } else {
                Identifier baseIdentifier = assetIdentifiers.remove(null);
                if (baseIdentifier != null)
                    unbakedAssets.put(null, loadUnbakedAsset(resourceManager, null, null, baseIdentifier));

                if (!assetIdentifiers.isEmpty()) { // contains sub models
                    LinkedHashMap<Identifier, List<ModelOverride.Condition>> overrideConditions = new LinkedHashMap<>();
                    for (Item item : this.items) {
                        Identifier itemIdentifier = Registry.ITEM.getId(item);
                        overrideConditions.put(new Identifier(itemIdentifier.getNamespace(), "item/" + itemIdentifier.getPath()), Collections.emptyList());

                        Identifier itemModelIdentifier = new Identifier(itemIdentifier.getNamespace(), "models/item/" + itemIdentifier.getPath() + ".json");
                        try (Resource itemModelResource = resourceManager.getResource(itemModelIdentifier); Reader resourceReader = new InputStreamReader(itemModelResource.getInputStream())) {
                            JsonUnbakedModel itemModelJson = JsonUnbakedModel.deserialize(resourceReader);

                            if (itemModelJson.getOverrides() != null && !itemModelJson.getOverrides().isEmpty())
                                for (ModelOverride override : itemModelJson.getOverrides())
                                    overrideConditions.put(override.getModelId(), override.streamConditions().toList());
                        }
                    }

                    ArrayList<Identifier> overrideModels = new ArrayList<>(overrideConditions.keySet());
                    Collections.reverse(overrideModels);

                    for (Identifier overrideModel : overrideModels) {
                        Identifier replacement = assetIdentifiers.remove(overrideModel);
                        if (replacement == null)
                            continue;

                        List<ModelOverride.Condition> conditions = overrideConditions.get(overrideModel);
                        unbakedAssets.put(conditions, loadUnbakedAsset(resourceManager, null, null, replacement));
                    }
                }
            }
        } catch (Exception e) {
            throw new CITLoadException(pack.resourcePack, propertiesIdentifier, (e.getClass() == Exception.class ? "" : e.getClass().getSimpleName() + ": ") + e.getMessage());
        } finally {
            assetIdentifiers = null;
        }
    }

    private JsonUnbakedModel loadUnbakedAsset(ResourceManager resourceManager, Map<java.lang.String, Either<SpriteIdentifier, String>> textureOverrideMap, Supplier<JsonUnbakedModel> itemModelGenerator, Identifier identifier) throws Exception {
        JsonUnbakedModel json;
        if (identifier.getPath().endsWith(".json")) {
            InputStream is = null;
            Resource resource = null;
            try {
                json = JsonUnbakedModel.deserialize(IOUtils.toString(is = (resource = resourceManager.getResource(identifier)).getInputStream(), StandardCharsets.UTF_8));
                json.id = identifier.toString();
                json.id = json.id.substring(0, json.id.length() - 5);

                ((JsonUnbakedModelAccessor) json).getTextureMap().replaceAll((layer, original) -> {
                    Optional<SpriteIdentifier> left = original.left();
                    if (left.isPresent()) {
                        String originalPath = left.get().getTextureId().getPath();
                        String[] split = originalPath.split("/");
                        if (originalPath.startsWith("./") || (split.length > 2 && split[1].equals("cit"))) {
                            Identifier resolvedIdentifier = resolvePath(identifier, originalPath, ".png", id -> pack.resourcePack.contains(ResourceType.CLIENT_RESOURCES, id));
                            if (resolvedIdentifier != null)
                                return Either.left(new SpriteIdentifier(left.get().getAtlasId(), new ResewnTextureIdentifier(resolvedIdentifier)));
                        }
                    }
                    return original;
                });

                Identifier parentId = ((JsonUnbakedModelAccessor) json).getParentId();
                if (parentId != null) {
                    String[] parentIdPathSplit = parentId.getPath().split("/");
                    if (parentId.getPath().startsWith("./") || (parentIdPathSplit.length > 2 && parentIdPathSplit[1].equals("cit"))) {
                        parentId = resolvePath(identifier, parentId.getPath(), ".json", id -> pack.resourcePack.contains(ResourceType.CLIENT_RESOURCES, id));
                        if (parentId != null)
                            ((JsonUnbakedModelAccessor) json).setParentId(new ResewnItemModelIdentifier(parentId));
                    }
                }

                json.getOverrides().replaceAll(override -> {
                    String[] modelIdPathSplit = override.getModelId().getPath().split("/");
                    if (override.getModelId().getPath().startsWith("./") || (modelIdPathSplit.length > 2 && modelIdPathSplit[1].equals("cit"))) {
                        Identifier resolvedOverridePath = resolvePath(identifier, override.getModelId().getPath(), ".json", id -> pack.resourcePack.contains(ResourceType.CLIENT_RESOURCES, id));
                        if (resolvedOverridePath != null)
                            return new ModelOverride(new ResewnItemModelIdentifier(resolvedOverridePath), override.streamConditions().collect(Collectors.toList()));
                    }

                    return override;
                });

                return json;
            } finally {
                IOUtils.closeQuietly(is, resource);
            }
        } else if (identifier.getPath().endsWith(".png")) {
            json = itemModelGenerator.get();
            if (json == null)
                json = new JsonUnbakedModel(new Identifier("minecraft", "item/generated"), new ArrayList<>(), ImmutableMap.of("layer0", Either.left(new SpriteIdentifier(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE, new ResewnTextureIdentifier(identifier)))), true, JsonUnbakedModel.GuiLight.ITEM, ModelTransformation.NONE, new ArrayList<>());
            json.getOverrides().clear();
            json.id = identifier.toString();
            json.id = json.id.substring(0, json.id.length() - 4);

            ((JsonUnbakedModelAccessor) json).getTextureMap().replaceAll((layerName, originalTextureEither) -> {
                if (textureOverrideMap.size() > 0) {
                    Either<SpriteIdentifier, String> textureOverride = textureOverrideMap.get(layerName);
                    return textureOverride == null ? originalTextureEither : textureOverride;
                } else
                    return Either.left(new SpriteIdentifier(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE, new ResewnTextureIdentifier(identifier)));
            });
            return json;
        }

        throw new Exception("Unknown asset type");
    }

    public Identifier fixDeprecatedSubItem(String subItem) {
        String replacement = switch (subItem) {
            case "bow_pulling_standby" -> "bow";
            case "crossbow_standby" -> "crossbow";
            case "potion_bottle_drinkable" -> "potion";
            case "potion_bottle_splash" -> "splash_potion";
            case "potion_bottle_lingering" -> "lingering_potion";


            default -> null;
        };

        if (replacement != null) {
            CITResewn.logWarnLoading("CIT Warning: Using deprecated sub item id \"" + subItem + "\" instead of \"" + replacement + "\" in " + pack.resourcePack.getName() + " -> " + propertiesIdentifier.toString());

            return new Identifier("minecraft", "item/" + replacement);
        }

        return null;
    }

    public BakedModel getItemModel(ItemStack stack, Hand hand, ClientWorld world, LivingEntity entity, int seed) {
        if (test(stack, hand, world, entity)) {
            return bakedSubModels.apply(this.bakedModel, stack, world, entity, seed);
        }

        return null;
    }

    public static class CITOverrideList extends ModelOverrideList {
        public void override(List<ModelOverride.Condition> key, BakedModel bakedModel) {
            Set<Identifier> conditionTypes = new HashSet<>(Arrays.asList(this.conditionTypes));
            for (ModelOverride.Condition condition : key)
                conditionTypes.add(condition.getType());
            this.conditionTypes = conditionTypes.toArray(new Identifier[0]);

            this.overrides = Arrays.copyOf(this.overrides, this.overrides.length + 1);

            Object2IntMap<Identifier> object2IntMap = new Object2IntOpenHashMap<>();
            for(int i = 0; i < this.conditionTypes.length; ++i)
                object2IntMap.put(this.conditionTypes[i], i);

            this.overrides[this.overrides.length - 1] = new BakedOverride(
                    key.stream()
                        .map((condition) -> new InlinedCondition(object2IntMap.getInt(condition.getType()), condition.getThreshold()))
                        .toArray(InlinedCondition[]::new)
                    , bakedModel);
        }
    }
}
