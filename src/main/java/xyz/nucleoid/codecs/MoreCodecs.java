package xyz.nucleoid.codecs;

import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.predicate.BlockPredicate;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.GameMode;
import net.minecraft.world.gen.stateprovider.BlockStateProvider;
import net.minecraft.world.gen.stateprovider.SimpleBlockStateProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class MoreCodecs {
    public static final Codec<ItemStack> ITEM_STACK = Codec.either(ItemStack.CODEC, Registry.ITEM.getCodec())
            .xmap(either -> either.map(Function.identity(), ItemStack::new), Either::left);

    public static final Codec<BlockState> BLOCK_STATE = Codec.either(BlockState.CODEC, Registry.BLOCK.getCodec())
            .xmap(either -> either.map(Function.identity(), Block::getDefaultState), Either::left);

    public static final Codec<BlockStateProvider> BLOCK_STATE_PROVIDER = Codec.either(BlockStateProvider.TYPE_CODEC, BLOCK_STATE)
            .xmap(either -> either.map(Function.identity(), SimpleBlockStateProvider::of), Either::left);

    public static final Codec<Text> TEXT = withJson(
            Text.Serializer::toJsonTree,
            json -> {
                Text text = Text.Serializer.fromJson(json);
                return text != null ? DataResult.success(text) : DataResult.error("Malformed text");
            }
    );

    public static final Codec<DyeColor> DYE_COLOR = stringVariants(DyeColor.values(), DyeColor::getName);
    public static final Codec<EquipmentSlot> EQUIPMENT_SLOT = stringVariants(EquipmentSlot.values(), EquipmentSlot::getName);
    public static final Codec<Formatting> FORMATTING = stringVariants(Formatting.values(), Formatting::getName);
    public static final Codec<GameMode> GAME_MODE = stringVariants(GameMode.values(), GameMode::getName);

    public static final Codec<TextColor> TEXT_COLOR = Codec.STRING.comapFlatMap(
            string -> {
                TextColor color = TextColor.parse(string);
                return color != null ? DataResult.success(color) : DataResult.error("Malformed TextColor");
            },
            TextColor::toString
    );

    public static final Codec<UUID> UUID_STRING = Codec.STRING.comapFlatMap(
            string -> {
                try {
                    return DataResult.success(UUID.fromString(string));
                } catch (IllegalArgumentException e) {
                    return DataResult.error("Malformed UUID string");
                }
            },
            UUID::toString
    );

    public static final Codec<BlockPredicate> BLOCK_PREDICATE = withJson(BlockPredicate::toJson, json -> {
        try {
            return DataResult.success(BlockPredicate.fromJson(json));
        } catch (JsonSyntaxException e) {
            return DataResult.error(e.getMessage());
        }
    });

    public static <T> Codec<T[]> arrayOrUnit(Codec<T> codec, IntFunction<T[]> factory) {
        return listToArray(listOrUnit(codec), factory);
    }

    public static <T> Codec<List<T>> listOrUnit(Codec<T> codec) {
        return Codec.either(codec.listOf(), codec)
                .xmap(
                        either -> either.map(Function.identity(), MoreCodecs::unitArrayList),
                        list -> list.size() == 1 ? Either.right(list.get(0)) : Either.left(list)
                );
    }

    public static <T> Codec<T[]> listToArray(Codec<List<T>> codec, IntFunction<T[]> factory) {
        return codec.xmap(list -> list.toArray(factory.apply(0)), Arrays::asList);
    }

    public static <A> Codec<A> stringVariants(A[] values, Function<A, String> asName) {
        return keyedVariants(values, asName, Codec.STRING);
    }

    public static <A, K> Codec<A> keyedVariants(A[] values, Function<A, K> asKey, Codec<K> keyCodec) {
        Map<K, A> byKey = new Object2ObjectOpenHashMap<>();
        for (A value : values) {
            byKey.put(asKey.apply(value), value);
        }

        return keyCodec.comapFlatMap(key -> {
            A value = byKey.get(key);
            return value != null ? DataResult.success(value) : DataResult.error("No variant with key '" + key + "'");
        }, asKey);
    }

    public static <A> Codec<A> withJson(Function<A, JsonElement> encode, Function<JsonElement, DataResult<A>> decode) {
        return withOps(JsonOps.INSTANCE, encode, decode);
    }

    public static <A> Codec<A> withNbt(Function<A, NbtElement> encode, Function<NbtElement, DataResult<A>> decode) {
        return withOps(NbtOps.INSTANCE, encode, decode);
    }

    public static <A> Codec<A> withNbt(
            BiFunction<A, NbtCompound, NbtCompound> encode,
            BiConsumer<A, NbtCompound> decode,
            Supplier<A> factory
    ) {
        return withNbt(
                value -> encode.apply(value, new NbtCompound()),
                tag -> {
                    if (tag instanceof NbtCompound) {
                        A value = factory.get();
                        decode.accept(value, (NbtCompound) tag);
                        return DataResult.success(value);
                    }
                    return DataResult.error("Expected compound tag");
                }
        );
    }

    public static <A, T> Codec<A> withOps(DynamicOps<T> ops, Function<A, T> encode, Function<T, DataResult<A>> decode) {
        return new MappedOpsCodec<>(ops, encode, decode);
    }

    public static <K, V> Codec<Map<K, V>> dispatchByMapKey(Codec<K> keyCodec, Function<K, Codec<V>> valueCodec) {
        return new DispatchMapCodec<>(keyCodec, valueCodec);
    }

    public static <T> Codec<RegistryKey<T>> registryKey(RegistryKey<? extends Registry<T>> registry) {
        return Identifier.CODEC.xmap(
                id -> RegistryKey.of(registry, id),
                RegistryKey::getValue
        );
    }

    public static <T> Codec<T> validate(Codec<T> codec, Function<T, DataResult<T>> validate) {
        return codec.flatXmap(validate, validate);
    }

    public static <T> Codec<T> validate(Codec<T> codec, Predicate<T> validate, String error) {
        return validate(codec, value -> {
            if (validate.test(value)) {
                return DataResult.success(value);
            } else {
                return DataResult.error(error);
            }
        });
    }

    private static <T> List<T> unitArrayList(T t) {
        List<T> list = new ArrayList<>(1);
        list.add(t);
        return list;
    }

    /**
     * Returns a {@link MapCodec} to decode the given {@link Codec} as a field. This functions very similar to
     * {@link Codec#optionalFieldOf(String, Object)}, however, when encountering an error, it will propagate this
     * instead of returning {@link Optional#empty()}. This is useful when failed parsing should be handled instead of
     * ignored.
     *
     * @param codec the field codec to use
     * @param name the name of the field to parse
     * @param defaultSupplier a supplier for a default value if not present
     * @param <T> the codec parse type
     * @return a {@link MapCodec} that decodes the specified field
     */
    public static <T> MapCodec<T> propagatingOptionalFieldOf(Codec<T> codec, String name, Supplier<? extends T> defaultSupplier) {
        return new PropagatingOptionalFieldCodec<>(name, codec)
                .xmap(opt -> opt.orElseGet(defaultSupplier), Optional::of);
    }

    /**
     * Returns a {@link MapCodec} to decode the given {@link Codec} as a field. This functions very similar to
     * {@link Codec#optionalFieldOf(String, Object)}, however, when encountering an error, it will propagate this
     * instead of returning {@link Optional#empty()}. This is useful when failed parsing should be handled instead of
     * ignored.
     *
     * @param codec the field codec to use
     * @param name the name of the field to parse
     * @param defaultValue a default value if not present
     * @param <T> the codec parse type
     * @return a {@link MapCodec} that decodes the specified field
     */
    public static <T> MapCodec<T> propagatingOptionalFieldOf(Codec<T> codec, String name, T defaultValue) {
        return new PropagatingOptionalFieldCodec<>(name, codec)
                .xmap(opt -> opt.orElse(defaultValue), Optional::of);
    }
}
