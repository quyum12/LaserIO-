package com.direwolf20.laserio.util;

import com.direwolf20.laserio.common.blockentities.LaserNodeBE;
import com.direwolf20.laserio.common.items.cards.BaseCard;
import com.direwolf20.laserio.common.items.cards.CardEnergy;
import com.direwolf20.laserio.common.items.cards.CardFluid;
import com.direwolf20.laserio.common.items.cards.CardItem;
import com.direwolf20.laserio.integration.mekanism.common.items.cards.CardChemical;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.List;

public class ExtractorCardCache extends BaseCardCache {
    public final int extractAmt;
    public final int tickSpeed;
    public int remainingSleep;
    public boolean exact;
    public int roundRobin;
    public int energyReceivedExternally;

    // Incremental state
    public int currentSlot = 0;
    public int currentInserterIndex = 0;
    public int currentInserterSlot = 0;
    public boolean isCheckingEmptySlots = false;
    public ItemStack extractingStack = ItemStack.EMPTY;
    public FluidStack extractingFluid = FluidStack.EMPTY;
    public TransferResult currentTransferResult = new TransferResult();
    public List<InserterCardCache> cachedPossibleInserters = null;
    public int currentResultIndex = 0;
    public int currentFilterIndex = 0;
    public boolean isRegulating = false;

    public enum TransferStep {
        SCAN_SLOTS,
        SCAN_INSERTERS,
        EXECUTE_TRANSFER,
        REGULATE,
        SCAN_FILTER
    }
    public TransferStep currentStep = TransferStep.SCAN_SLOTS;

    public void resetSlotState() {
        currentSlot = 0;
        currentInserterIndex = 0;
        currentInserterSlot = 0;
        extractingStack = ItemStack.EMPTY;
        extractingFluid = FluidStack.EMPTY;
        currentTransferResult = new TransferResult();
        cachedPossibleInserters = null;
        currentResultIndex = 0;
        currentFilterIndex = 0;
        currentStep = TransferStep.SCAN_SLOTS;
    }

    public ExtractorCardCache(Direction direction, ItemStack cardItem, int cardSlot, LaserNodeBE be) {
        super(direction, cardItem, cardSlot, be);
        switch(cardType) {
            case ITEM -> {
                this.extractAmt = CardItem.getItemExtractAmt(cardItem);
                this.tickSpeed = CardItem.getExtractSpeed(cardItem);
            }
            case FLUID -> {
                this.extractAmt = CardFluid.getFluidExtractAmt(cardItem);
                this.tickSpeed = CardFluid.getExtractSpeed(cardItem);
            }
            case ENERGY -> {
                this.extractAmt = CardEnergy.getEnergyExtractAmt(cardItem);
                this.tickSpeed = CardEnergy.getExtractSpeed(cardItem);
            }
            case CHEMICAL -> {
                this.extractAmt = CardChemical.getChemicalExtractAmt(cardItem);
                this.tickSpeed = CardChemical.getExtractSpeed(cardItem);
            }
            default -> {
                this.extractAmt = 0;
                this.tickSpeed = 1200;
            }
        }
        this.exact = BaseCard.getExact(cardItem);
        this.roundRobin = BaseCard.getRoundRobin(cardItem);
        this.energyReceivedExternally = 0;
    }

    public int decrementSleep() {
        remainingSleep--;
        if (remainingSleep < 0) {
            remainingSleep = 0;
        }
        return remainingSleep;
    }
}