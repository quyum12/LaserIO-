package com.direwolf20.laserio.integration.mekanism.util;

import com.direwolf20.laserio.common.containers.customhandler.FilterCountHandler;
import com.direwolf20.laserio.common.items.filters.FilterBasic;
import com.direwolf20.laserio.common.items.filters.FilterCount;
import com.direwolf20.laserio.common.items.filters.FilterMod;
import com.direwolf20.laserio.common.items.filters.FilterTag;
import com.direwolf20.laserio.util.BaseCardCache;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.ChemicalType;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MekanismCardCache {
    public final BaseCardCache baseCardCache;
    public final List<ChemicalStack<?>> filteredChemicals;
    public final List<ChemicalStackKey> filteredChemicalsKeys;
    public final Map<ChemicalStackKey, Boolean> filterCacheChemical = new Object2BooleanOpenHashMap<>();
    public final Map<ChemicalStackKey, Integer> filterCountsChemical = new Object2IntOpenHashMap<>();
    private final ChemicalStackKey lookupKey = new ChemicalStackKey();

    public MekanismCardCache(BaseCardCache baseCardCache) {
        this.baseCardCache = baseCardCache;
        if (this.baseCardCache.filterCard.isEmpty()) {
            filteredChemicals = new ArrayList<>();
            filteredChemicalsKeys = new ArrayList<>();
        } else {
            this.filteredChemicals = getFilteredChemicals();
            this.filteredChemicalsKeys = new ArrayList<>();
            for (ChemicalStack<?> stack : filteredChemicals) {
                filteredChemicalsKeys.add(new ChemicalStackKey(stack));
            }
        }
    }

    public List<ChemicalStack<?>> getFilteredChemicals() {
        List<ChemicalStack<?>> filteredChemicals = new ArrayList<>();
        ItemStackHandler filterSlotHandler;
        ItemStack filterCard = baseCardCache.filterCard;
        if (filterCard.getItem() instanceof FilterBasic)
            filterSlotHandler = FilterBasic.getInventory(filterCard);
        else
            filterSlotHandler = FilterCount.getInventory(filterCard);
        for (int i = 0; i < filterSlotHandler.getSlots(); i++) {
            ItemStack itemStack = filterSlotHandler.getStackInSlot(i);
            if (!itemStack.isEmpty()) {
                for (ChemicalType chemicalType : ChemicalType.values()) {
                    LazyOptional<? extends IChemicalHandler<?, ?>> chemicalHandlerOptional = itemStack.getCapability(MekanismStatics.getCapabilityForChemical(chemicalType));
                    if (!chemicalHandlerOptional.isPresent())
                        continue;
                    IChemicalHandler<?, ?> chemicalHandler = chemicalHandlerOptional.resolve().get();
                    for (int tank = 0; tank < chemicalHandler.getTanks(); tank++) {
                        ChemicalStack<?> chemicalStack = chemicalHandler.getChemicalInTank(tank);
                        if (!chemicalStack.isEmpty())
                            filteredChemicals.add(chemicalStack); //If this is a basic card it'll always be one, but getFilterAmt handles the proper logic of returning a value
                    }
                }
            }
        }
        return filteredChemicals;
    }

    public boolean isStackValidForCard(ChemicalStack<?> testStack) {
        ItemStack filterCard = baseCardCache.filterCard;
        if (filterCard.isEmpty()) return true; //If theres no filter in the card
        lookupKey.set(testStack);
        Boolean cachedResult = filterCacheChemical.get(lookupKey);
        if (cachedResult != null) return cachedResult;

        if (filterCard.getItem() instanceof FilterMod) {
            String modId = testStack.getTypeRegistryName().getNamespace();
            for (ChemicalStack<?> stack : filteredChemicals) {
                if (stack.getTypeRegistryName().getNamespace().equals(modId)) {
                    ChemicalStackKey key = new ChemicalStackKey(testStack);
                    filterCacheChemical.put(key, baseCardCache.isAllowList);
                    return baseCardCache.isAllowList;
                }
            }
        } else if (filterCard.getItem() instanceof FilterTag) {
            for (TagKey<?> tagKey : testStack.getType().getTags().toList()) {
                String tag = tagKey.location().toString().toLowerCase(Locale.ROOT);
                if (baseCardCache.filterTags.contains(tag)) {
                    ChemicalStackKey key = new ChemicalStackKey(testStack);
                    filterCacheChemical.put(key, baseCardCache.isAllowList);
                    return baseCardCache.isAllowList;
                }
            }
        } else {
            for (ChemicalStackKey keyInFilter : filteredChemicalsKeys) {
                if (keyInFilter.equals(lookupKey)) {
                    ChemicalStackKey key = new ChemicalStackKey(testStack);
                    filterCacheChemical.put(key, baseCardCache.isAllowList);
                    return baseCardCache.isAllowList;
                }
            }
        }
        ChemicalStackKey key = new ChemicalStackKey(testStack);
        filterCacheChemical.put(key, !baseCardCache.isAllowList);
        return !baseCardCache.isAllowList;
    }

    public int getFilterAmt(ChemicalStack<?> testStack) {
        ItemStack filterCard = baseCardCache.filterCard;
        if (filterCard.isEmpty())
            return 0; //If theres no filter in the card (This should never happen in theory)
        if (!(filterCard.getItem() instanceof FilterCount)) { //If this is a basic or tag Card return -1 which will mean infinite amount
            return -1;
        }
        lookupKey.set(testStack);
        Integer cachedCount = filterCountsChemical.get(lookupKey);
        if (cachedCount != null) //If we've already tested this, get it from the cache
            return cachedCount;

        FilterCountHandler filterSlotHandler = FilterCount.getInventory(filterCard);
        for (int i = 0; i < filterSlotHandler.getSlots(); i++) { //Gotta iterate the card's NBT because of the way we store amounts (in the MBAmt tag)
            ItemStack itemStack = filterSlotHandler.getStackInSlot(i);
            if (!itemStack.isEmpty()) {
                ChemicalStack<?> chemicalStack = MekanismStatics.getFirstChemicalOnItemStack(itemStack);
                if (chemicalStack.isEmpty()) continue;
                ChemicalStackKey innerLookup = new ChemicalStackKey();
                if (innerLookup.set(chemicalStack).equals(lookupKey)) {
                    int mbAmt = FilterCount.getSlotAmount(filterCard, i);
                    ChemicalStackKey key = new ChemicalStackKey(testStack);
                    filterCountsChemical.put(key, mbAmt);
                    return mbAmt;
                }

            }
        }
        ChemicalStackKey key = new ChemicalStackKey(testStack);
        filterCountsChemical.put(key, 0);
        return 0; //Should never get here in theory
    }
}