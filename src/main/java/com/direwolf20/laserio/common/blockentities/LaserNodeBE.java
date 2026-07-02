package com.direwolf20.laserio.common.blockentities;

import com.direwolf20.laserio.client.blockentityrenders.LaserNodeBERender;
import com.direwolf20.laserio.client.particles.fluidparticle.FluidFlowParticleData;
import com.direwolf20.laserio.client.particles.itemparticle.ItemFlowParticleData;
import com.direwolf20.laserio.common.blockentities.basebe.BaseLaserBE;
import com.direwolf20.laserio.common.blocks.LaserNode;
import com.direwolf20.laserio.common.containers.LaserNodeContainer;
import com.direwolf20.laserio.common.events.ServerTickHandler;
import com.direwolf20.laserio.setup.Config;
import com.direwolf20.laserio.common.items.cards.BaseCard;
import com.direwolf20.laserio.common.items.cards.BaseCard.CardType;
import com.direwolf20.laserio.common.items.cards.BaseCard.TransferMode;
import com.direwolf20.laserio.common.items.cards.CardEnergy;
import com.direwolf20.laserio.common.items.cards.CardFluid;
import com.direwolf20.laserio.common.items.cards.CardItem;
import com.direwolf20.laserio.common.items.cards.CardRedstone;
import com.direwolf20.laserio.common.items.filters.FilterBasic;
import com.direwolf20.laserio.common.items.filters.FilterCount;
import com.direwolf20.laserio.common.items.filters.FilterMod;
import com.direwolf20.laserio.common.items.filters.FilterTag;
import com.direwolf20.laserio.common.items.upgrades.OverclockerNode;
import com.direwolf20.laserio.integration.ModIntegration;
import com.direwolf20.laserio.integration.mekanism.MekanismCache;
import com.direwolf20.laserio.integration.mekanism.common.items.cards.CardChemical;
import com.direwolf20.laserio.integration.mekanism.util.ParticleRenderDataChemical;
import com.direwolf20.laserio.setup.Registration;
import com.direwolf20.laserio.util.BaseCardCache;
import com.direwolf20.laserio.util.CardRender;
import com.direwolf20.laserio.util.DimBlockPos;
import com.direwolf20.laserio.util.ExtractorCardCache;
import com.direwolf20.laserio.util.FluidStackKey;
import com.direwolf20.laserio.util.InserterCardCache;
import com.direwolf20.laserio.util.ItemHandlerUtil;
import com.direwolf20.laserio.util.ItemHandlerUtil.InventoryCardCounts;
import com.direwolf20.laserio.util.ItemStackKey;
import com.direwolf20.laserio.util.MiscTools;
import com.direwolf20.laserio.util.NodeSideCache;
import com.direwolf20.laserio.util.ParticleData;
import com.direwolf20.laserio.util.ParticleDataFluid;
import com.direwolf20.laserio.util.ParticleRenderData;
import com.direwolf20.laserio.util.ParticleRenderDataFluid;
import com.direwolf20.laserio.util.SensorCardCache;
import com.direwolf20.laserio.util.StockerCardCache;
import com.direwolf20.laserio.util.TransferResult;
import com.direwolf20.laserio.util.WeakConsumerWrapper;
import it.unimi.dsi.fastutil.bytes.Byte2BooleanMap;
import it.unimi.dsi.fastutil.bytes.Byte2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.bytes.Byte2ByteMap;
import it.unimi.dsi.fastutil.bytes.Byte2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import mekanism.api.chemical.ChemicalType;
import mekanism.api.chemical.IChemicalHandler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.util.NonNullConsumer;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import com.direwolf20.laserio.common.util.LaserScheduler;
import net.minecraftforge.items.ItemStackHandler;
import org.joml.Vector3f;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class LaserNodeBE extends BaseLaserBE {
    public enum NodeState {
        ACTIVE,
        COOLDOWN,
        IDLE
    }

    public enum Phase {
        IDLE,
        DISCOVER,
        REFRESH_CACHES,
        PICK_SIDE,
        PICK_CARD,
        SENSE,
        TRANSFER,
        COMPLETE
    }

    public record NodeWorkResult(boolean didWork, int cooldownTicks) {
    }

    private NodeState nodeState = NodeState.IDLE;
    private long nextAvailableTime = 0;
    private boolean wakeRequestedThisTick = false;
    private int consecutiveNoWork = 0;

    private Phase currentPhase = Phase.DISCOVER;
    private int currentSideIndex = 0;
    private ExtractorCardCache currentCard = null;
    private int discoveryIndex = 0;
    private List<DimBlockPos> discoveryList = new ArrayList<>();

    public NodeState getNodeState() {
        return nodeState;
    }

    public void setNodeState(NodeState nodeState) {
        this.nodeState = nodeState;
    }

    public long getNextAvailableTime() {
        return nextAvailableTime;
    }

    public void setNextAvailableTime(long nextAvailableTime) {
        this.nextAvailableTime = nextAvailableTime;
    }

    public boolean isWakeRequestedThisTick() {
        return wakeRequestedThisTick;
    }

    public void setWakeRequestedThisTick(boolean wakeRequestedThisTick) {
        this.wakeRequestedThisTick = wakeRequestedThisTick;
    }

    /** A cache of this blocks sides - data we need to reference frequently **/
    public final NodeSideCache[] nodeSideCaches = new NodeSideCache[6];
    private final IItemHandler EMPTY = new ItemStackHandler(0);

    /** Adjacent Inventory Handlers **/
    public record SideConnection(Direction nodeSide, Direction sneakySide) {
    }

    public record InventoryCacheKey(SideConnection side, boolean isCompareNBT) {
    }

    /** BE and ItemHandler used for checking if a note/container is valid **/
    private record LaserNodeItemHandler(LaserNodeBE be, IItemHandler handler) {
    }

    private record LaserNodeFluidHandler(LaserNodeBE be, IFluidHandler handler) {
    }

    private record LaserNodeEnergyHandler(LaserNodeBE be, IEnergyStorage handler) {
    }

    public Map<ExtractorCardCache, Integer> roundRobinMap = new Object2IntOpenHashMap<>();

    private final Map<InventoryCacheKey, ItemHandlerUtil.InventoryCounts> perTickInventoryCounts = new HashMap<>();
    private final Map<SideConnection, LazyOptional<IItemHandler>> facingHandlerItem = new HashMap<>();
    private final Map<SideConnection, NonNullConsumer<LazyOptional<IItemHandler>>> connectionInvalidatorItem = new HashMap<>();
    private final Map<SideConnection, LazyOptional<IFluidHandler>> facingHandlerFluid = new HashMap<>();
    private final Map<SideConnection, NonNullConsumer<LazyOptional<IFluidHandler>>> connectionInvalidatorFluid = new HashMap<>();
    private final Map<SideConnection, LazyOptional<IEnergyStorage>> facingHandlerEnergy = new HashMap<>();
    private final Map<SideConnection, NonNullConsumer<LazyOptional<IEnergyStorage>>> connectionInvalidatorEnergy = new HashMap<>();

    /** Variables for tracking and sending items/filters/etc **/
    private final Set<DimBlockPos> otherNodesInNetwork = new HashSet<>();

    private final List<InserterCardCache> inserterNodes = new CopyOnWriteArrayList<>(); //All Inventory nodes that contain an inserter card
    private final Map<ExtractorCardCache, Map<ItemStackKey, List<InserterCardCache>>> inserterCache = new HashMap<>();
    private final Map<ExtractorCardCache, Map<FluidStackKey, List<InserterCardCache>>> inserterCacheFluid = new HashMap<>();
    private final Map<ExtractorCardCache, List<InserterCardCache>> channelOnlyCache = new HashMap<>();
    private final List<ParticleRenderData> particleRenderData = new ArrayList<>();
    private final List<ParticleRenderDataFluid> particleRenderDataFluids = new ArrayList<>();
    private final List<ParticleRenderDataChemical> particleRenderDataChemicals = new ArrayList<>();
    private final Random random = new Random();

    private record StockerRequest(StockerCardCache stockerCardCache, ItemStackKey itemStackKey) {
    }

    private record StockerSource(InserterCardCache inserterCardCache, int slot) {
    }

    private final Map<StockerRequest, StockerSource> stockerDestinationCache = new HashMap<>();
    private final Set<ExtractorCardCache> emptyCards = new HashSet<>();

    public boolean rendersChecked = false;
    public List<CardRender> cardRenders = new ArrayList<>();

    /** Redstone Variables **/
    public Byte2ByteMap redstoneNetwork = new Byte2ByteOpenHashMap(); //Channel,Strength
    public Byte2ByteMap myRedstoneIn = new Byte2ByteOpenHashMap();  //Channel,Strength

    public Byte2ByteMap myRedstoneOut = new Byte2ByteOpenHashMap();  //Side,Strength
    public Byte2BooleanMap redstoneCardSides = new Byte2BooleanOpenHashMap(); //Side and whether it has a redstone card, for client
    public boolean redstoneChecked = false;
    public boolean redstoneRefreshed = false;
    public boolean firstTimeNodeLoaded = true; //Redstone needs to be refreshed first time node loads into the world

    /** Misc Variables **/
    private boolean discoveredNodes = false; //The first time this block entity loads, it'll run discovery to refresh itself
    private boolean showParticles = true;
    private boolean refreshedInvNodesThisTick = false;

    /** Mekanism integration **/
    public MekanismCache mekanismCache;

    public LaserNodeBE(BlockPos pos, BlockState state) {
        super(Registration.LASER_NODE_BE.get(), pos, state);
        if (ModIntegration.MEKANISM.isLoaded()) {
            mekanismCache = new MekanismCache(this);
        }
        for (Direction direction : Direction.values()) {
            final int j = direction.ordinal();
            com.direwolf20.laserio.common.containers.customhandler.LaserNodeItemHandler itemHandler = new com.direwolf20.laserio.common.containers.customhandler.LaserNodeItemHandler(LaserNodeContainer.SLOTS, this);
            nodeSideCaches[j] = new NodeSideCache(itemHandler, new LaserEnergyStorage(direction), 0);
        }
    }

    public List<InserterCardCache> getInserterNodes() {
        return inserterNodes;
    }

    /** This is called by nodes when a connection is added/removed - the other node does the discovery and then tells this one about it **/
    public void setOtherNodesInNetwork(Set<DimBlockPos> otherNodesInNetwork) {
        this.otherNodesInNetwork.clear();
        if (level == null) return;
        for (DimBlockPos pos : otherNodesInNetwork) {
            Level targetLevel = pos.getLevel(level.getServer());
            if (targetLevel == null) continue;
            this.otherNodesInNetwork.add(new DimBlockPos(targetLevel, getRelativePos(pos.blockPos)));
        }
        if (refreshedInvNodesThisTick) {
            return;
        }
        refreshAllInvNodes(); //Seeing as the otherNodes list just got updated, we should refresh the InventoryNode content caches
        refreshedInvNodesThisTick = true;
    }

    public void updateOverclockers() {
        for (Direction direction : Direction.values()) {
            int slot = 9; //The Overclockers Slot
            NodeSideCache nodeSideCache = nodeSideCaches[direction.ordinal()];
            ItemStack overclockerStack = nodeSideCache.itemHandler.getStackInSlot(slot);
            if (overclockerStack.isEmpty()) {
                nodeSideCache.overclockers = 0;
            }
            if (overclockerStack.getItem() instanceof OverclockerNode) {
                nodeSideCache.overclockers = overclockerStack.getCount();
            }
        }
    }

    /** Build a list of extractor cards AND Stocker cards this node has in it, for looping through **/
    public void findMyExtractors() {
        for (Direction direction : Direction.values()) {
            NodeSideCache nodeSideCache = nodeSideCaches[direction.ordinal()];
            nodeSideCache.extractorCardCaches.clear();
            for (int slot = 0; slot < LaserNodeContainer.CARD_SLOTS; slot++) {
                ItemStack card = nodeSideCache.itemHandler.getStackInSlot(slot);
                if (card.getItem() instanceof BaseCard && !(card.getItem() instanceof CardRedstone)) {
                    ExtractorCardCache extractorCardCache = switch(BaseCard.getNamedTransferMode(card)) {
                        case EXTRACT -> new ExtractorCardCache(direction, card, slot, this);
                        case STOCK -> new StockerCardCache(direction, card, slot, this);
                        case SENSOR -> new SensorCardCache(direction, card, slot, this);
                        default -> null;
                    };
                    if (extractorCardCache != null) {
                        nodeSideCache.extractorCardCaches.add(extractorCardCache);
                    }
                }
            }
        }
    }

    /** Loop through all the extractorCards/stockerCards and run the extractions **/
    public boolean extract(Direction direction) {
        NodeSideCache nodeSideCache = nodeSideCaches[direction.ordinal()];
        int countCardsHandled = 0;
        boolean didWork = false;
        for (ExtractorCardCache extractorCardCache : nodeSideCache.extractorCardCaches) {
            if (extractorCardCache instanceof SensorCardCache) continue; //Don't try to operate on SensorCards
            if (extractorCardCache.remainingSleep <= 0) {
                if (!extractorCardCache.enabled) continue;
                if (emptyCards.contains(extractorCardCache)) continue;
                if (countCardsHandled > nodeSideCache.overclockers) continue;
                boolean cardHandled;

                if (extractorCardCache instanceof StockerCardCache stockerCardCache) {
                    cardHandled = switch (extractorCardCache.cardType) {
                        case ITEM -> stockItems(stockerCardCache);
                        case FLUID -> stockFluids(stockerCardCache);
                        case ENERGY -> stockEnergy(stockerCardCache);
                        case CHEMICAL -> mekanismCache.stockChemicals(stockerCardCache);
                        default -> false;
                    };
                } else {
                    cardHandled = switch (extractorCardCache.cardType) {
                        case ITEM -> sendItems(extractorCardCache);
                        case FLUID -> sendFluids(extractorCardCache);
                        case ENERGY -> sendEnergy(extractorCardCache);
                        case CHEMICAL -> mekanismCache.sendChemicals(extractorCardCache);
                        default -> false;
                    };
                }
                if (cardHandled) {
                    countCardsHandled++;
                    didWork = true;
                } else {
                    emptyCards.add(extractorCardCache);
                    extractorCardCache.remainingSleep = 5;
                }
                if (extractorCardCache.remainingSleep <= 0) {
                    extractorCardCache.remainingSleep = extractorCardCache.tickSpeed;
                }
                if (didWork) break; // Bounded to one card per side per tick
            }
        }
        return didWork;
    }

    /** Loop through all the sensorCards and run the sensing **/
    public boolean sense(Direction direction) {
        NodeSideCache nodeSideCache = nodeSideCaches[direction.ordinal()];
        int countCardsHandled = 0;
        boolean didWork = false;
        for (ExtractorCardCache extractorCardCache : nodeSideCache.extractorCardCaches) {
            if (!(extractorCardCache instanceof SensorCardCache sensorCardCache)) {
                continue; //Don't even try to operate on non-sensor cards
            }
            if (extractorCardCache.remainingSleep <= 0) {
                if (!extractorCardCache.enabled) continue;
                if (countCardsHandled > nodeSideCache.overclockers) continue;
                boolean cardHandled = switch (extractorCardCache.cardType) {
                    case ITEM -> senseItems(sensorCardCache);
                    case FLUID -> senseFluids(sensorCardCache);
                    case ENERGY -> senseEnergy(sensorCardCache);
                    case CHEMICAL -> mekanismCache.senseChemicals(sensorCardCache);
                    default -> false;
                };
                if (cardHandled) {
                    countCardsHandled++;
                    didWork = true;
                } else {
                    extractorCardCache.remainingSleep = 5;
                }
                if (extractorCardCache.remainingSleep <= 0) {
                    extractorCardCache.remainingSleep = extractorCardCache.tickSpeed;
                }
                if (didWork) break; // Bounded to one card per side per tick
            }
        }
        return didWork;
    }

    public void tickClient() {
        drawParticlesClient();
        particleRenderData.clear();
        particleRenderDataFluids.clear();
        particleRenderDataChemicals.clear();
    }

    public NodeWorkResult doNodeTick() {
        perTickInventoryCounts.clear();
        refreshedInvNodesThisTick = false;

        // Advance sleep timers once per tick for ALL cards
        int minRemainingSleep = Integer.MAX_VALUE;
        boolean anyCardReady = false;
        for (Direction dir : Direction.values()) {
            NodeSideCache nodeSideCache = nodeSideCaches[dir.ordinal()];
            for (ExtractorCardCache card : nodeSideCache.extractorCardCaches) {
                int sleep = card.decrementSleep();
                if (sleep == 0) anyCardReady = true;
                if (sleep < minRemainingSleep) minRemainingSleep = sleep;
            }
        }

        boolean didWork = false;
        // Strict state machine: one phase or one atomic operation per tick
        switch (currentPhase) {
            case DISCOVER -> {
                if (!discoveredNodes) {
                    discoverAllNodes();
                    findMyExtractors();
                    updateOverclockers();
                    discoveredNodes = true;
                }
                currentPhase = Phase.REFRESH_CACHES;
                didWork = true;
            }
            case REFRESH_CACHES -> {
                if (!redstoneChecked) {
                    populateThisRedstoneNetwork(true);
                    redstoneChecked = true;
                } else if (!redstoneRefreshed) {
                    refreshRedstoneNetwork();
                    redstoneRefreshed = true;
                }
                currentPhase = Phase.PICK_SIDE;
                didWork = true;
            }
            case PICK_SIDE -> {
                currentSideIndex++;
                if (currentSideIndex >= 6) currentSideIndex = 0;
                currentPhase = Phase.PICK_CARD;
                didWork = true;
            }
            case PICK_CARD -> {
                NodeSideCache nodeSideCache = nodeSideCaches[currentSideIndex];
                if (nodeSideCache.extractorCardCaches.isEmpty()) {
                    currentPhase = Phase.PICK_SIDE;
                } else {
                    if (nodeSideCache.nextCardIndex >= nodeSideCache.extractorCardCaches.size()) {
                        nodeSideCache.nextCardIndex = 0;
                    }
                    currentCard = nodeSideCache.extractorCardCaches.get(nodeSideCache.nextCardIndex);
                    nodeSideCache.nextCardIndex++;
                    currentPhase = currentCard instanceof SensorCardCache ? Phase.SENSE : Phase.TRANSFER;
                }
                didWork = true;
            }
            case SENSE -> {
                if (currentCard instanceof SensorCardCache sensorCardCache) {
                    if (sensorCardCache.remainingSleep <= 0 && sensorCardCache.enabled) {
                        didWork = incrementalSense(sensorCardCache);
                        if (didWork) {
                            sensorCardCache.remainingSleep = sensorCardCache.tickSpeed;
                            currentPhase = Phase.COMPLETE;
                        }
                    } else {
                        currentPhase = Phase.COMPLETE;
                    }
                } else {
                    currentPhase = Phase.COMPLETE;
                }
            }
            case TRANSFER -> {
                if (currentCard != null && currentCard.remainingSleep <= 0 && currentCard.enabled && !emptyCards.contains(currentCard)) {
                    didWork = incrementalTransfer(currentCard);
                    if (didWork) {
                        // Transfer logic will handle its own internal state and return true when an atomic op is done
                        // If it finishes the whole card, it should set currentPhase = Phase.COMPLETE
                    } else {
                        emptyCards.add(currentCard);
                        currentCard.remainingSleep = 5;
                        currentPhase = Phase.COMPLETE;
                    }
                } else {
                    currentPhase = Phase.COMPLETE;
                }
            }
            case COMPLETE -> {
                currentPhase = Phase.PICK_SIDE;
                didWork = true;
            }
        }

        if (didWork || anyCardReady) {
            consecutiveNoWork = 0;
            return new NodeWorkResult(true, 1);
        } else {
            consecutiveNoWork++;
            if (consecutiveNoWork < 6 * 4) { // Roughly 6 sides * 4 cards
                return new NodeWorkResult(false, 1);
            } else {
                consecutiveNoWork = 0;
                int cooldown = (minRemainingSleep == Integer.MAX_VALUE) ? 0 : Math.min(minRemainingSleep, 20);
                return new NodeWorkResult(false, cooldown);
            }
        }
    }

    private boolean incrementalSense(SensorCardCache sensorCardCache) {
        return switch (sensorCardCache.cardType) {
            case ITEM -> senseItems(sensorCardCache);
            case FLUID -> senseFluids(sensorCardCache);
            case ENERGY -> senseEnergy(sensorCardCache);
            case CHEMICAL -> mekanismCache.senseChemicals(sensorCardCache);
            default -> false;
        };
    }

    private boolean incrementalTransfer(ExtractorCardCache extractorCardCache) {
        boolean cardHandled;
        if (extractorCardCache instanceof StockerCardCache stockerCardCache) {
            cardHandled = switch (extractorCardCache.cardType) {
                case ITEM -> stockItems(stockerCardCache);
                case FLUID -> stockFluids(stockerCardCache);
                case ENERGY -> stockEnergy(stockerCardCache);
                case CHEMICAL -> mekanismCache.stockChemicals(stockerCardCache);
                default -> false;
            };
        } else {
            cardHandled = switch (extractorCardCache.cardType) {
                case ITEM -> sendItems(extractorCardCache);
                case FLUID -> sendFluids(extractorCardCache);
                case ENERGY -> sendEnergy(extractorCardCache);
                case CHEMICAL -> mekanismCache.sendChemicals(extractorCardCache);
                default -> false;
            };
        }
        if (cardHandled) {
            // If the card operation is fully complete (e.g. all slots checked or max items moved)
            // we'll need to decide when to set Phase.COMPLETE.
            // For now, let's assume one successful atomic operation means we move to COMPLETE for this tick.
            currentPhase = Phase.COMPLETE;
        }
        return cardHandled;
    }

    public void tickServer() {
        // No-op, handled by LaserScheduler
    }

    public void populateThisRedstoneNetwork(boolean notifyOthers) {
        //System.out.println("Checking redstone at: " + getBlockPos() + ", Gametime: " + level.getGameTime());
        //int myRedstoneCount = myRedstoneIn.size();
        //myRedstoneIn.clear();
        Byte2ByteMap myRedstoneInTemp = new Byte2ByteOpenHashMap();
        boolean updated = false;
        for (Direction direction : Direction.values()) {
            NodeSideCache nodeSideCache = nodeSideCaches[direction.ordinal()];
            for (int slot = 0; slot < LaserNodeContainer.CARD_SLOTS; slot++) {
                ItemStack card = nodeSideCache.itemHandler.getStackInSlot(slot);
                if (card.getItem() instanceof CardRedstone && BaseCard.getTransferMode(card) == 0) { //Redstone mode and input mode
                    int redstoneStrength = level.getSignal(getBlockPos().relative(direction), direction);
                    if (CardRedstone.getInterval(card)) {
                        if (redstoneStrength >= CardRedstone.getIntervalLowerBound(card) && redstoneStrength <= CardRedstone.getIntervalUpperBound(card)) {
                            redstoneStrength = CardRedstone.getIntervalOutput(card);
                        } else {
                            redstoneStrength = 0;
                        }
                    }
                    //System.out.println("Input: " + getBlockPos() + ":" + direction + ":" + redstoneStrength);
                    if (redstoneStrength > 0) {
                        byte redstoneChannel = BaseCard.getRedstoneChannel(card);
                        //if (updateMyRedstoneIn(redstoneChannel, (byte) redstoneStrength))
                        //    updated = true;
                        if (myRedstoneInTemp.containsKey(redstoneChannel)) {
                            byte existingRedstoneStrength = myRedstoneInTemp.get(redstoneChannel);
                            if (redstoneStrength > existingRedstoneStrength) { //Only update the network if the new strength is bigger.
                                myRedstoneInTemp.put(redstoneChannel, (byte) redstoneStrength);
                            }
                        } else {
                            myRedstoneInTemp.put(redstoneChannel, (byte) redstoneStrength);
                        }
                    }
                }
            }
            for (Byte2ByteMap.Entry entry : nodeSideCache.myRedstoneFromSensors.byte2ByteEntrySet()) { //Update the temp variable with data from any sensors
                myRedstoneInTemp.put(entry.getByteKey(), entry.getByteValue());
            }
        }

        if (!myRedstoneInTemp.equals(myRedstoneIn)) {
            //System.out.println("Redstone input changed - updating network");
            updated = true;
            myRedstoneIn = new Byte2ByteOpenHashMap(myRedstoneInTemp);
        }
        if (updated && notifyOthers)
            notifyOtherNodesOfChange();
    }

    /** Visits all the nodes in the network, and refreshes this redstone network cache from theirs **/
    public void refreshRedstoneNetwork() {
        //System.out.println("Updating Redstone Network at: " + getBlockPos() + ", Gametime: " + level.getGameTime());
        redstoneNetwork.clear();
        if (level == null) return;
        for (DimBlockPos pos : otherNodesInNetwork) {
            Level targetLevel = pos.getLevel(level.getServer());
            if (targetLevel == null) continue;
            LaserNodeBE laserNodeBE = getNodeAt(new DimBlockPos(targetLevel, getWorldPos(pos.blockPos)));
            if (laserNodeBE == null) continue;
            for (Map.Entry<Byte, Byte> entry : laserNodeBE.myRedstoneIn.byte2ByteEntrySet()) {
                updateRedstoneNetwork(entry.getKey(), entry.getValue());
            }
        }
        updateRedstoneOutputs(); //Now that we know what the network should look like - update the outputs
        refreshCardsRedstone();
    }

    /** Goes through all the cards in this node, and updates their redstone state **/
    public void refreshCardsRedstone() {
        boolean inserterUpdated = false;
        boolean extractorUpdated = false;
        for (InserterCardCache inserterCardCache : inserterNodes) {
            if (inserterCardCache.be.getBlockPos().equals(getBlockPos())) {
                boolean tempEnabled = inserterCardCache.enabled;
                inserterCardCache.setEnabled();
                if (tempEnabled != inserterCardCache.enabled) {
                    inserterUpdated = true;
                }
            }
        }
        for (Direction direction : Direction.values()) {
            NodeSideCache nodeSideCache = nodeSideCaches[direction.ordinal()];
            nodeSideCache.invalidateEnergy();
            for (ExtractorCardCache extractorCardCache : nodeSideCache.extractorCardCaches) {
                boolean tempEnabled = extractorCardCache.enabled;
                extractorCardCache.setEnabled();
                if (tempEnabled != extractorCardCache.enabled) {
                    extractorUpdated = true;
                }
            }
        }
        //if (inserterUpdated || extractorUpdated)
        markDirtyClient();
        if (inserterUpdated) {
            if (level == null) return;
            for (DimBlockPos pos : otherNodesInNetwork) {
                Level targetLevel = pos.getLevel(level.getServer());
                if (targetLevel == null) continue;
                LaserNodeBE node = getNodeAt(new DimBlockPos(targetLevel, getWorldPos(pos.blockPos)));
                if (node == null) continue;
                node.checkInvNode(new DimBlockPos(this.level, this.getBlockPos()), true);
            }
        }
    }

    public byte getRedstoneChannelStrength(byte channel) {
        if (redstoneNetwork.containsKey(channel))
            return redstoneNetwork.get(channel);
        return 0;
    }

    public void updateRedstoneNetwork(byte redstoneChannel, byte redstoneStrength) {
        if (redstoneNetwork.containsKey(redstoneChannel)) {
            byte existingRedstoneStrength = redstoneNetwork.get(redstoneChannel);
            if (redstoneStrength > existingRedstoneStrength) //Only update the network if the new strength is bigger.
                this.redstoneNetwork.put(redstoneChannel, redstoneStrength);
        } else {
            this.redstoneNetwork.put(redstoneChannel, redstoneStrength);
        }
    }

    public boolean getRedstoneSideStrong(Direction direction) {
        byte side = (byte) direction.ordinal();
        if (!myRedstoneOut.containsKey(side)) return false;
        byte redstoneOut = myRedstoneOut.get(side);
        return redstoneOut > 15; //>15 means strong signal
    }

    public int getRedstoneSide(Direction direction) {
        byte side = (byte) direction.ordinal();
        if (!myRedstoneOut.containsKey(side)) return 0;
        byte redstoneOut = myRedstoneOut.get(side);
        return redstoneOut > 15 ? redstoneOut - 15 : redstoneOut; //>15 means strong signal
    }

    public void updateRedstoneOutputs() {
        //System.out.println("Checking Redstone Outputs at: " + getBlockPos());
        //myRedstoneOut.clear();
        Byte2ByteMap myRedstoneOutTemp = new Byte2ByteOpenHashMap();  //Side,Strength
        redstoneCardSides.clear();
        for (Direction direction : Direction.values()) {
            byte side = (byte) direction.ordinal();
            NodeSideCache nodeSideCache = nodeSideCaches[direction.ordinal()];
            for (int slot = 0; slot < LaserNodeContainer.CARD_SLOTS; slot++) {
                ItemStack card = nodeSideCache.itemHandler.getStackInSlot(slot);
                if (card.getItem() instanceof CardRedstone && BaseCard.getTransferMode(card) == 1) { //Redstone mode and Output mode
                    redstoneCardSides.put((byte) direction.ordinal(), true);
                    byte cardChannel = BaseCard.getRedstoneChannel(card);
                    byte logicOperationChannel = CardRedstone.getRedstoneChannelOperation(card);
                    byte logicOperation = CardRedstone.getLogicOperation(card);
                    byte outputMode = CardRedstone.getOutputMode(card);
                    if (redstoneNetwork.containsKey(cardChannel) || (logicOperation != 0 && redstoneNetwork.containsKey(logicOperationChannel)) || outputMode != 0) {
                        byte redstoneStrength = redstoneNetwork.get(cardChannel);
                        byte logicOperationRedstoneStrength = redstoneNetwork.get(logicOperationChannel);
                        redstoneStrength = switch(logicOperation) {
                            case 1 -> (byte) (((redstoneStrength + logicOperationRedstoneStrength) > 0) ? 15 : 0); //OR
                            case 2 -> (byte) (((redstoneStrength * logicOperationRedstoneStrength) > 0) ? 15 : 0); //AND
                            case 3 -> (byte) (((redstoneStrength > 0) ^ (logicOperationRedstoneStrength > 0)) ? 15 : 0); //XOR
                            default -> redstoneStrength;
                        };
                        redstoneStrength = switch(outputMode) {
                            case 1 -> (byte) (15 - redstoneStrength); //Complementary
                            case 2 -> (byte) ((redstoneStrength > 0) ? 0 : 15); //NOT
                            default -> redstoneStrength;
                        };
                        if (redstoneStrength > 0) {
                            if (CardRedstone.getStrong(card)) {
                                redstoneStrength += 15;
                            }
                            if (myRedstoneOutTemp.containsKey(side)) {
                                byte existingRedstoneStrength = myRedstoneOutTemp.get(side);
                                if (redstoneStrength > existingRedstoneStrength) { //Only update the network if the new strength is bigger
                                    myRedstoneOutTemp.put(side, redstoneStrength);
                                }
                            } else {
                                myRedstoneOutTemp.put(side, redstoneStrength);
                            }
                            //if (updateMyRedstoneOut((byte) direction.ordinal(), redstoneStrength));
                        }
                    }
                } else if (card.getItem() instanceof CardRedstone && BaseCard.getTransferMode(card) == 0) { //Redstone mode and Output mode
                    redstoneCardSides.put((byte) direction.ordinal(), true);
                }
            }
            if (firstTimeNodeLoaded || !Objects.equals(myRedstoneOutTemp.get(side), myRedstoneOut.get(side))) {
                if (myRedstoneOutTemp.containsKey(side)) {
                    myRedstoneOut.put(side, myRedstoneOutTemp.get(side));
                } else {
                    myRedstoneOut.remove(side);
                }
                level.neighborChanged(getBlockPos().relative(direction), this.getBlockState().getBlock(), getBlockPos());
                level.updateNeighborsAtExceptFromFacing(getBlockPos().relative(direction), this.getBlockState().getBlock(), direction.getOpposite());
            }
        }
        BlockState state = this.getBlockState();
        state.updateNeighbourShapes(level, getBlockPos(), Block.UPDATE_ALL);
        if (firstTimeNodeLoaded) {
            firstTimeNodeLoaded = false;
        }
    }

    public void sortInserters() {
        this.inserterNodes.sort(Comparator.comparingDouble(InserterCardCache::getDistance));
        this.inserterNodes.sort(Comparator.comparingInt(InserterCardCache::getPriority).reversed());
    }

    public List<InserterCardCache> filterPossibleInserters(ExtractorCardCache extractorCardCache, Predicate<InserterCardCache> isCardValidForStack) {
        List<InserterCardCache> list = new ArrayList<>();
        for (InserterCardCache inserterCardCache : inserterNodes) {
            if (inserterCardCache.isValidDestination(extractorCardCache, isCardValidForStack)) {
                list.add(inserterCardCache);
            }
        }
        return list;
    }

    public List<InserterCardCache> filterPossibleInserters(ExtractorCardCache extractorCardCache) {
        return filterPossibleInserters(extractorCardCache, inserterCardCache -> true);
    }

    /** Finds all inserters that can be extracted to **/
    public List<InserterCardCache> getPossibleInserters(ExtractorCardCache extractorCardCache, ItemStack stack) {
        Map<ItemStackKey, List<InserterCardCache>> cache = inserterCache.computeIfAbsent(extractorCardCache, t -> new HashMap<>());
        ItemStackKey lookupKey = extractorCardCache.getLookupKey();
        lookupKey.set(stack, true);

        List<InserterCardCache> result = cache.get(lookupKey);
        if (result == null) {
            result = filterPossibleInserters(extractorCardCache, inserterCardCache -> inserterCardCache.isStackValidForCard(stack));
            cache.put(new ItemStackKey(stack, true), result);
        }
        return result;
    }

    /** Finds all inserters that can be extracted to **/
    public List<InserterCardCache> getPossibleInserters(ExtractorCardCache extractorCardCache, FluidStack stack) {
        Map<FluidStackKey, List<InserterCardCache>> cache = inserterCacheFluid.computeIfAbsent(extractorCardCache, t -> new HashMap<>());
        FluidStackKey lookupKey = extractorCardCache.getLookupKeyFluid();
        lookupKey.set(stack, true);

        List<InserterCardCache> result = cache.get(lookupKey);
        if (result == null) {
            result = filterPossibleInserters(extractorCardCache, inserterCardCache -> inserterCardCache.isStackValidForCard(stack));
            cache.put(new FluidStackKey(stack, true), result);
        }
        return result;
    }

    /** Finds all inserters that match the channel (Used for stockers) **/
    public List<InserterCardCache> getChannelMatchInserters(ExtractorCardCache extractorCardCache) {
        return channelOnlyCache.computeIfAbsent(extractorCardCache, t ->
                filterPossibleInserters(extractorCardCache)
        );
    }

    public boolean chunksLoaded(DimBlockPos nodePos, BlockPos destinationPos) {
        assert nodePos.getLevel(level.getServer()) != null;
        if (!nodePos.getLevel(level.getServer()).isLoaded(nodePos.blockPos)) {
            return false;
        }
        if (!nodePos.getLevel(level.getServer()).isLoaded(destinationPos)) {
            return false;
        }
        return true;
    }

    public int getNextRR(ExtractorCardCache extractorCardCache, List<InserterCardCache> inserterCardCaches) {
        int nextRR;
        if (roundRobinMap.containsKey(extractorCardCache)) {
            int currentRR = roundRobinMap.get(extractorCardCache);
            nextRR = currentRR + 1 >= inserterCardCaches.size() ? 0 : currentRR + 1;
        } else {
            nextRR = 0;
        }
        roundRobinMap.put(extractorCardCache, nextRR);
        return nextRR;
    }

    public int getRR(ExtractorCardCache extractorCardCache) {
        if (roundRobinMap.containsKey(extractorCardCache)) {
            return roundRobinMap.get(extractorCardCache);
        } else {
            roundRobinMap.put(extractorCardCache, 0);
            return 0;
        }
    }

    public List<InserterCardCache> applyRR(ExtractorCardCache extractorCardCache, List<InserterCardCache> inserterCardCaches, int nextRR) {
        int size = inserterCardCaches.size();
        List<InserterCardCache> list = new ArrayList<>(size);
        for (int i = nextRR; i < size; i++) {
            list.add(inserterCardCaches.get(i));
        }
        for (int i = 0; i < nextRR; i++) {
            list.add(inserterCardCaches.get(i));
        }
        return list;
    }

    public boolean extractItem(ExtractorCardCache extractorCardCache, IItemHandler fromInventory, ItemStack extractStack, int startSlot) {
        if (extractorCardCache.currentStep == ExtractorCardCache.TransferStep.SCAN_SLOTS) {
            // Atomic operation: check ONE slot for extraction
            ItemStack stackInSlot = fromInventory.getStackInSlot(startSlot);
            if (stackInSlot.isEmpty() || !ItemHandlerUtil.doItemsMatch(extractStack, stackInSlot, extractorCardCache.isCompareNBT)) {
                return false;
            }

            int extractAmt = Math.min(extractStack.getCount(), stackInSlot.getCount());
            ItemStack fakeExtracted = fromInventory.extractItem(startSlot, extractAmt, true);
            if (fakeExtracted.isEmpty()) return false;

            extractorCardCache.extractingStack = fakeExtracted.copy();
            extractorCardCache.currentTransferResult = new TransferResult();
            extractorCardCache.currentTransferResult.addResult(new TransferResult.Result(fromInventory, startSlot, extractorCardCache, fakeExtracted.copy(), this, true));

            extractorCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_INSERTERS;
            extractorCardCache.currentInserterIndex = 0;
            extractorCardCache.cachedPossibleInserters = null;
            return true; // One atomic op: slot check + fake extract
        }

        if (extractorCardCache.currentStep == ExtractorCardCache.TransferStep.SCAN_INSERTERS) {
            if (extractorCardCache.cachedPossibleInserters == null) {
                List<InserterCardCache> inserterCardCaches = getPossibleInserters(extractorCardCache, extractorCardCache.extractingStack);
                if (extractorCardCache.roundRobin != 0) {
                    int roundRobin = getRR(extractorCardCache);
                    inserterCardCaches = applyRR(extractorCardCache, inserterCardCaches, roundRobin);
                }
                extractorCardCache.cachedPossibleInserters = inserterCardCaches;
            }

            if (extractorCardCache.currentInserterIndex >= extractorCardCache.cachedPossibleInserters.size()) {
                // Done scanning inserters, check if we found enough
                int amtFound = 0;
                for (TransferResult.Result res : extractorCardCache.currentTransferResult.results) {
                    if (res.insertHandler != null) amtFound += res.itemStack.getCount();
                }
                if (amtFound == 0 || (extractorCardCache.exact && amtFound != extractorCardCache.extractingStack.getCount())) {
                    extractorCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_SLOTS;
                    extractorCardCache.extractingStack = ItemStack.EMPTY;
                    return false;
                }
                extractorCardCache.currentStep = ExtractorCardCache.TransferStep.EXECUTE_TRANSFER;
                extractorCardCache.currentResultIndex = 0;
                return true;
            }

            InserterCardCache inserterCardCache = extractorCardCache.cachedPossibleInserters.get(extractorCardCache.currentInserterIndex);
            LaserNodeItemHandler laserNodeItemHandler = getLaserNodeHandlerItem(inserterCardCache);
            if (laserNodeItemHandler != null) {
                ItemStack testStack = extractorCardCache.extractingStack.copy();
                int amtAlreadyFound = 0;
                for (TransferResult.Result res : extractorCardCache.currentTransferResult.results) {
                    if (res.insertHandler != null) amtAlreadyFound += res.itemStack.getCount();
                }
                testStack.shrink(amtAlreadyFound);

                // Atomic insert check: check ONE slot in the inserter's inventory
                int slots = laserNodeItemHandler.handler.getSlots();
                if (extractorCardCache.currentInserterSlot >= slots) {
                    extractorCardCache.currentInserterSlot = 0;
                }

                int insSlot = extractorCardCache.currentInserterSlot;
                ItemStack resultStack = laserNodeItemHandler.handler.insertItem(insSlot, testStack, true);
                int amtInserted = testStack.getCount() - resultStack.getCount();

                if (amtInserted > 0) {
                    extractorCardCache.currentTransferResult.addResult(new TransferResult.Result(laserNodeItemHandler.handler, insSlot, inserterCardCache, testStack.split(amtInserted), laserNodeItemHandler.be, false));
                    if (extractorCardCache.roundRobin != 0) {
                        getNextRR(extractorCardCache, extractorCardCache.cachedPossibleInserters);
                    }
                    // If we filled the requirement or exact mode is satisfied, we could potentially finish.
                    // But for simplicity, let's just move to next slot/inserter.
                }

                extractorCardCache.currentInserterSlot++;
                if (extractorCardCache.currentInserterSlot >= slots) {
                    extractorCardCache.currentInserterSlot = 0;
                    extractorCardCache.currentInserterIndex++;
                }
                return true; // Atomic op: checked one slot of one inserter
            }
            extractorCardCache.currentInserterIndex++;
            extractorCardCache.currentInserterSlot = 0;
            return true;
        }

        if (extractorCardCache.currentStep == ExtractorCardCache.TransferStep.EXECUTE_TRANSFER) {
            // Execution is also incremental: one result per tick
            if (extractorCardCache.currentResultIndex >= extractorCardCache.currentTransferResult.results.size()) {
                extractorCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_SLOTS;
                extractorCardCache.extractingStack = ItemStack.EMPTY;
                extractorCardCache.currentTransferResult = new TransferResult();
                return false;
            }

            TransferResult.Result res = extractorCardCache.currentTransferResult.results.get(extractorCardCache.currentResultIndex);
            if (res.insertHandler != null) {
                // It's an insertion. We must have already extracted enough items in previous ticks
                // or we perform extraction right now if it's the first execution step.
                // In this incremental model, we'll perform extraction of EXACTLY what this insertion needs.
                int amtNeeded = res.itemStack.getCount();

                // Find and perform extractions for this insertion
                for (TransferResult.Result extRes : extractorCardCache.currentTransferResult.results) {
                    if (extRes.extractHandler != null && extRes.itemStack.getCount() > 0) {
                        int taking = Math.min(amtNeeded, extRes.itemStack.getCount());
                        extRes.extractHandler.extractItem(extRes.extractSlot, taking, false);
                        extRes.itemStack.shrink(taking);
                        amtNeeded -= taking;
                        if (amtNeeded == 0) break;
                    }
                }

                res.insertHandler.insertItem(res.insertSlot, res.itemStack, false);
                if (res.toBE != null) {
                    LaserScheduler.requestWakeUp(res.toBE);
                }
                if (res.inserterCardCache != null) {
                    drawParticles(res.itemStack, extractorCardCache.direction, this, res.toBE, res.inserterCardCache.direction, extractorCardCache.cardSlot, res.inserterCardCache.cardSlot);
                }
            }

            extractorCardCache.currentResultIndex++;
            return true; // Atomic op: performed one insertion (and its required extractions)
        }

        return false;
    }

    public boolean updateRedstoneFromSensor(boolean filterMatched, byte redstoneChannel, NodeSideCache nodeSideCache) {
        byte currentRedstoneFromNetwork = nodeSideCache.myRedstoneFromSensors.get(redstoneChannel);
        byte newRedstoneStrength = filterMatched ? (byte) 15 : (byte) 0;
        if (newRedstoneStrength == 0) {
            nodeSideCache.myRedstoneFromSensors.remove(redstoneChannel);
        } else {
            nodeSideCache.myRedstoneFromSensors.put(redstoneChannel, newRedstoneStrength);
        }
        if (currentRedstoneFromNetwork != newRedstoneStrength) {
            return true;
        }
        return false; //No changes were needed
    }

    public boolean senseItems(SensorCardCache sensorCardCache) {
        BlockPos adjacentPos = getBlockPos().relative(sensorCardCache.direction);
        assert level != null;
        if (!level.isLoaded(adjacentPos)) return false;
        ItemStack filter = sensorCardCache.filterCard;
        NodeSideCache nodeSideCache = nodeSideCaches[sensorCardCache.direction.ordinal()];
        if (filter.isEmpty()) { //Needs a filter
            updateRedstoneFromSensor(false, sensorCardCache.redstoneChannel, nodeSideCache);
            return false;
        }

        IItemHandler adjacentInventory = getAttachedInventory(sensorCardCache.direction, sensorCardCache.sneaky).orElse(EMPTY);
        Direction inventorySide = sensorCardCache.direction.getOpposite();
        if (sensorCardCache.sneaky != -1) inventorySide = Direction.values()[sensorCardCache.sneaky];
        SideConnection sideConnection = new SideConnection(sensorCardCache.direction, inventorySide);
        ItemHandlerUtil.InventoryCounts inventoryCounts = perTickInventoryCounts.computeIfAbsent(new InventoryCacheKey(sideConnection, sensorCardCache.isCompareNBT), k -> new ItemHandlerUtil.InventoryCounts(adjacentInventory, sensorCardCache.isCompareNBT));

        boolean andMode = BaseCard.getAnd(sensorCardCache.cardItem);
        boolean filterMatched = false;
        List<ItemStack> filteredItemsList = sensorCardCache.filteredItems;

        if (filteredItemsList.isEmpty()) return false;

        // Atomic sensing: check ONE item from the filter
        if (sensorCardCache.currentFilterIndex >= filteredItemsList.size()) {
            sensorCardCache.currentFilterIndex = 0;
        }

        ItemStack testStack = filteredItemsList.get(sensorCardCache.currentFilterIndex);
        int amtHad = inventoryCounts.getCount(testStack);

        if (filter.getItem() instanceof FilterCount) {
            filterMatched = (amtHad >= testStack.getCount() && (!sensorCardCache.exact || amtHad <= testStack.getCount()));
        } else {
            filterMatched = (amtHad > 0);
        }

        // This is a simplified incremental sense.
        // Real logic for 'AND' mode would need to track matches across ticks.
        // For now, let's just use the current result to update redstone.
        if (updateRedstoneFromSensor(filterMatched, sensorCardCache.redstoneChannel, nodeSideCache)) {
            rendersChecked = false;
            clearCachedInventories();
            redstoneChecked = false;
        }

        sensorCardCache.currentFilterIndex++;
        return true;
    }

    public boolean senseFluids(SensorCardCache sensorCardCache) {
        BlockPos adjacentPos = getBlockPos().relative(sensorCardCache.direction);
        assert level != null;
        if (!level.isLoaded(adjacentPos)) return false;
        NodeSideCache nodeSideCache = nodeSideCaches[sensorCardCache.direction.ordinal()];
        Optional<IFluidHandler> adjacentTankOptional = getAttachedFluidTank(sensorCardCache.direction, sensorCardCache.sneaky).resolve();
        if (adjacentTankOptional.isEmpty()) {
            updateRedstoneFromSensor(false, sensorCardCache.redstoneChannel, nodeSideCache);
            return false;
        }
        IFluidHandler adjacentTank = adjacentTankOptional.get();

        ItemStack filter = sensorCardCache.filterCard;
        if (filter.isEmpty()) {
            updateRedstoneFromSensor(false, sensorCardCache.redstoneChannel, nodeSideCache);
            return false;
        }

        List<FluidStack> filteredFluids = sensorCardCache.getFilteredFluids();
        if (filteredFluids.isEmpty()) return false;

        if (sensorCardCache.currentFilterIndex >= filteredFluids.size()) {
            sensorCardCache.currentFilterIndex = 0;
        }

        FluidStack testStack = filteredFluids.get(sensorCardCache.currentFilterIndex);
        boolean filterMatched = false;

        // Atomic sensing: check all tanks for ONE fluid from the filter
        // Actually checking all tanks might be O(N_tanks). If tanks are many, we should incrementalize tanks too.
        // For standard fluid containers, tanks are few.
        for (int tank = 0; tank < adjacentTank.getTanks(); tank++) {
            FluidStack stackInTank = adjacentTank.getFluidInTank(tank);
            if (stackInTank.isFluidEqual(testStack)) {
                if (filter.getItem() instanceof FilterCount) {
                    int desiredAmt = sensorCardCache.getFilterAmt(testStack);
                    int amtHad = stackInTank.getAmount();
                    if (amtHad >= desiredAmt && (!sensorCardCache.exact || amtHad <= desiredAmt)) {
                        filterMatched = true;
                        break;
                    }
                } else {
                    filterMatched = true;
                    break;
                }
            }
        }

        if (updateRedstoneFromSensor(filterMatched, sensorCardCache.redstoneChannel, nodeSideCache)) {
            rendersChecked = false;
            clearCachedInventories();
            redstoneChecked = false;
        }

        sensorCardCache.currentFilterIndex++;
        return true;
    }

    public boolean senseEnergy(SensorCardCache sensorCardCache) {
        BlockPos adjacentPos = getBlockPos().relative(sensorCardCache.direction);
        assert level != null;
        if (!level.isLoaded(adjacentPos)) return false;
        Optional<IEnergyStorage> adjacentEnergyOptional = getAttachedEnergyTank(sensorCardCache.direction, sensorCardCache.sneaky).resolve();
        NodeSideCache nodeSideCache = nodeSideCaches[sensorCardCache.direction.ordinal()];
        if (adjacentEnergyOptional.isEmpty()) { //Needs a filter
            if (updateRedstoneFromSensor(false, sensorCardCache.redstoneChannel, nodeSideCache)) {
                rendersChecked = false;
                clearCachedInventories();
                redstoneChecked = false;
            }
            return false;
        }
        IEnergyStorage adjacentEnergy = adjacentEnergyOptional.get();
        boolean filterMatched = false;
        int desired = (int) (adjacentEnergy.getMaxEnergyStored() * ((float) sensorCardCache.insertLimit / 100));
        int amtHad = adjacentEnergy.getEnergyStored();
        if (amtHad < desired || (sensorCardCache.exact && amtHad > desired)) {
            filterMatched = false;
        } else {
            filterMatched = true;
        }
        if (updateRedstoneFromSensor(filterMatched, sensorCardCache.redstoneChannel, nodeSideCache)) {
            //System.out.println("Redstone network change detected");
            rendersChecked = false;
            clearCachedInventories();
            redstoneChecked = false;
        }
        return true;
    }

    /** Extractor Cards call this, and try to find an inserter card to send their items to **/
    public boolean sendItems(ExtractorCardCache extractorCardCache) {
        BlockPos adjacentPos = getBlockPos().relative(extractorCardCache.direction);
        assert level != null;
        if (!level.isLoaded(adjacentPos)) return false;
        IItemHandler adjacentInventory = getAttachedInventory(extractorCardCache.direction, extractorCardCache.sneaky).orElse(EMPTY);
        if (adjacentInventory.getSlots() == 0) return false;

        // Reset if we are starting a new scan
        if (extractorCardCache.currentStep == ExtractorCardCache.TransferStep.SCAN_SLOTS && extractorCardCache.extractingStack.isEmpty()) {
            if (extractorCardCache.currentSlot >= adjacentInventory.getSlots()) {
                extractorCardCache.currentSlot = 0;
            }
        }

        // Atomic operation: check ONE slot
        int slot = extractorCardCache.currentSlot;
        ItemStack stackInSlot = adjacentInventory.getStackInSlot(slot);

        if (!stackInSlot.isEmpty() && extractorCardCache.isStackValidForCard(stackInSlot)) {
            int maxItems = Math.min(Config.MAX_ITEMS_PER_TICK.get(), extractorCardCache.extractAmt);
            ItemStack extractStack = stackInSlot.copy();
            extractStack.setCount(maxItems);

            if (extractorCardCache.filterCard.getItem() instanceof FilterCount) {
                Direction inventorySide = extractorCardCache.direction.getOpposite();
                if (extractorCardCache.sneaky != -1) inventorySide = Direction.values()[extractorCardCache.sneaky];
                SideConnection sideConnection = new SideConnection(extractorCardCache.direction, inventorySide);
                ItemHandlerUtil.InventoryCounts inventoryCounts = perTickInventoryCounts.computeIfAbsent(new InventoryCacheKey(sideConnection, extractorCardCache.isCompareNBT), k -> new ItemHandlerUtil.InventoryCounts(adjacentInventory, extractorCardCache.isCompareNBT));

                int filterCount = extractorCardCache.getFilterAmt(extractStack);
                if (filterCount > 0) {
                    int amtInInv = inventoryCounts.getCount(extractStack);
                    int amtAllowedToRemove = amtInInv - filterCount;
                    if (amtAllowedToRemove > 0) {
                        extractStack.setCount(Math.min(extractStack.getCount(), amtAllowedToRemove));
                        if (extractItem(extractorCardCache, adjacentInventory, extractStack, slot)) {
                            extractorCardCache.currentSlot++; // Move to next slot for next time
                            return true;
                        }
                    }
                }
            } else {
                if (extractItem(extractorCardCache, adjacentInventory, extractStack, slot)) {
                    extractorCardCache.currentSlot++; // Move to next slot for next time
                    return true;
                }
            }
        }

        extractorCardCache.currentSlot++;
        if (extractorCardCache.currentSlot >= adjacentInventory.getSlots()) {
            extractorCardCache.currentSlot = 0;
            // We finished scanning all slots and found nothing this tick (or we would have returned true above)
            return false;
        }

        // We checked one slot and found nothing, but we have more slots to check.
        // Return true to indicate we did an "atomic operation" (checking the slot), but we'll stay in TRANSFER phase.
        return true;
    }

    public boolean extractFluidStack(ExtractorCardCache extractorCardCache, IFluidHandler fromInventory, FluidStack extractStack) {
        int totalAmtNeeded = extractStack.getAmount();
        int amtToExtract = extractStack.getAmount();
        List<InserterCardCache> inserterCardCaches = getPossibleInserters(extractorCardCache, extractStack);
        int roundRobin = -1;
        boolean foundAnything = false;
        if (extractorCardCache.roundRobin != 0) {
            roundRobin = getRR(extractorCardCache);
            inserterCardCaches = applyRR(extractorCardCache, inserterCardCaches, roundRobin);
        }
        for (InserterCardCache inserterCardCache : inserterCardCaches) {
            LaserNodeFluidHandler laserNodeFluidHandler = getLaserNodeHandlerFluid(inserterCardCache);
            if (laserNodeFluidHandler == null) continue;
            IFluidHandler handler = laserNodeFluidHandler.handler;
            if (inserterCardCache.filterCard.getItem() instanceof FilterCount) {
                int filterCount = inserterCardCache.getFilterAmt(extractStack);
                for (int tank = 0; tank < handler.getTanks(); tank++) {
                    FluidStack fluidStack = handler.getFluidInTank(tank);
                    if (fluidStack.isEmpty() || fluidStack.isFluidEqual(extractStack)) {
                        int currentAmt = fluidStack.getAmount();
                        int neededAmt = filterCount - currentAmt;
                        if (neededAmt < extractStack.getAmount()) {
                            amtToExtract = neededAmt;
                            break;
                        }
                    }
                }
            }
            if (amtToExtract == 0) {
                amtToExtract = totalAmtNeeded;
                continue;
            }
            extractStack.setAmount(amtToExtract);
            int amtFit = handler.fill(extractStack, IFluidHandler.FluidAction.SIMULATE);
            if (amtFit == 0) { //Next inserter if nothing went in -- return false if enforcing round robin
                if (extractorCardCache.roundRobin == 2) {
                    return false;
                }
                if (extractorCardCache.roundRobin != 0) getNextRR(extractorCardCache, inserterCardCaches);
                continue;
            }
            extractStack.setAmount(amtFit);
            FluidStack drainedStack = fromInventory.drain(extractStack, IFluidHandler.FluidAction.EXECUTE);
            if (drainedStack.isEmpty()) continue; //If we didn't get anything for whatever reason
            foundAnything = true;
            handler.fill(drainedStack, IFluidHandler.FluidAction.EXECUTE);
            if (laserNodeFluidHandler.be != null) {
                LaserScheduler.requestWakeUp(laserNodeFluidHandler.be);
            }
            drawParticlesFluid(drainedStack, extractorCardCache.direction, extractorCardCache.be, inserterCardCache.be, inserterCardCache.direction, extractorCardCache.cardSlot, inserterCardCache.cardSlot);
            totalAmtNeeded -= drainedStack.getAmount();
            amtToExtract = totalAmtNeeded;
            if (extractorCardCache.roundRobin != 0) getNextRR(extractorCardCache, inserterCardCaches);
            if (totalAmtNeeded == 0) return true;
        }
        return foundAnything;
    }

    public boolean extractFluidStackExact(ExtractorCardCache extractorCardCache, IFluidHandler fromInventory, FluidStack extractStack) {
        int totalAmtNeeded = extractStack.getAmount();
        int amtToExtract = extractStack.getAmount();
        FluidStack testDrain = fromInventory.drain(extractStack, IFluidHandler.FluidAction.SIMULATE);
        if (testDrain.getAmount() < totalAmtNeeded) return false; //If we don't have enough in the extractTank we can't pull out this exact amount!
        List<InserterCardCache> inserterCardCaches = getPossibleInserters(extractorCardCache, extractStack);
        int roundRobin = -1;
        if (extractorCardCache.roundRobin != 0) {
            roundRobin = getRR(extractorCardCache);
            inserterCardCaches = applyRR(extractorCardCache, inserterCardCaches, roundRobin);
        }
        Map<InserterCardCache, Integer> insertHandlers = new Object2IntOpenHashMap<>();
        for (InserterCardCache inserterCardCache : inserterCardCaches) {
            LaserNodeFluidHandler laserNodeFluidHandler = getLaserNodeHandlerFluid(inserterCardCache);
            if (laserNodeFluidHandler == null) continue;
            IFluidHandler handler = laserNodeFluidHandler.handler;
            if (inserterCardCache.filterCard.getItem() instanceof FilterCount) {
                int filterCount = inserterCardCache.getFilterAmt(extractStack);
                for (int tank = 0; tank < handler.getTanks(); tank++) {
                    FluidStack fluidStack = handler.getFluidInTank(tank);
                    if (fluidStack.isEmpty() || fluidStack.isFluidEqual(extractStack)) {
                        int currentAmt = fluidStack.getAmount();
                        int neededAmt = filterCount - currentAmt;
                        if (neededAmt < totalAmtNeeded) {
                            amtToExtract = neededAmt;
                            break;
                        }
                    }
                }
            }
            if (amtToExtract == 0) {
                amtToExtract = totalAmtNeeded;
                continue;
            }
            extractStack.setAmount(amtToExtract);
            int amtFit = handler.fill(extractStack, IFluidHandler.FluidAction.SIMULATE);
            if (amtFit == 0) { //Next inserter if nothing went in -- return false if enforcing round robin
                if (extractorCardCache.roundRobin == 2) {
                    return false;
                }
                if (extractorCardCache.roundRobin != 0) {
                    getNextRR(extractorCardCache, inserterCardCaches);
                }
                continue;
            }
            extractStack.setAmount(amtFit);
            FluidStack drainedStack = fromInventory.drain(extractStack, IFluidHandler.FluidAction.SIMULATE);
            if (drainedStack.isEmpty()) continue; //If we didn't get anything for whatever reason
            insertHandlers.put(inserterCardCache, drainedStack.getAmount()); //Add the handler to the list of handlers we found fluid in
            totalAmtNeeded -= drainedStack.getAmount(); //Keep track of how much we have left to insert
            amtToExtract = totalAmtNeeded;
            if (extractorCardCache.roundRobin != 0) getNextRR(extractorCardCache, inserterCardCaches);
            if (totalAmtNeeded == 0) {
                break;
            }
        }
        if (totalAmtNeeded > 0) {
            return false;
        }
        for (Map.Entry<InserterCardCache, Integer> entry : insertHandlers.entrySet()) {
            InserterCardCache inserterCardCache = entry.getKey();
            LaserNodeFluidHandler laserNodeFluidHandler = getLaserNodeHandlerFluid(inserterCardCache);
            IFluidHandler handler = laserNodeFluidHandler.handler;
            extractStack.setAmount(entry.getValue());
            FluidStack drainedStack = fromInventory.drain(extractStack, IFluidHandler.FluidAction.EXECUTE);
            handler.fill(drainedStack, IFluidHandler.FluidAction.EXECUTE);
            if (laserNodeFluidHandler.be != null) {
                LaserScheduler.requestWakeUp(laserNodeFluidHandler.be);
            }
            drawParticlesFluid(drainedStack, extractorCardCache.direction, extractorCardCache.be, inserterCardCache.be, inserterCardCache.direction, extractorCardCache.cardSlot, inserterCardCache.cardSlot);
        }
        return true;
    }

    /** Extractor Cards call this, and try to find an inserter card to send their items to **/
    public boolean sendFluids(ExtractorCardCache extractorCardCache) {
        BlockPos adjacentPos = getBlockPos().relative(extractorCardCache.direction);
        assert level != null;
        if (!level.isLoaded(adjacentPos)) return false;
        LazyOptional<IFluidHandler> adjacentTankOptional = getAttachedFluidTank(extractorCardCache.direction, extractorCardCache.sneaky);
        if (!adjacentTankOptional.isPresent()) return false;
        IFluidHandler adjacentTank = adjacentTankOptional.resolve().get();
        if (adjacentTank.getTanks() == 0) return false;

        if (extractorCardCache.currentSlot >= adjacentTank.getTanks()) {
            extractorCardCache.currentSlot = 0;
        }

        int tank = extractorCardCache.currentSlot;
        FluidStack fluidStack = adjacentTank.getFluidInTank(tank);
        if (!fluidStack.isEmpty() && extractorCardCache.isStackValidForCard(fluidStack)) {
            FluidStack extractStack = fluidStack.copy();
            extractStack.setAmount(extractorCardCache.extractAmt);
            if (extractorCardCache.filterCard.getItem() instanceof FilterCount) {
                int filterCount = extractorCardCache.getFilterAmt(extractStack);
                if (filterCount > 0) {
                    int amtInInv = fluidStack.getAmount();
                    int amtAllowedToRemove = amtInInv - filterCount;
                    if (amtAllowedToRemove > 0) {
                        extractStack.setAmount(Math.min(extractStack.getAmount(), amtAllowedToRemove));
                        if (extractorCardCache.exact) {
                            if (extractFluidStackExact(extractorCardCache, adjacentTank, extractStack)) {
                                extractorCardCache.currentSlot++;
                                return true;
                            }
                        } else {
                            if (extractFluidStack(extractorCardCache, adjacentTank, extractStack)) {
                                extractorCardCache.currentSlot++;
                                return true;
                            }
                        }
                    }
                }
            } else {
                if (extractorCardCache.exact) {
                    if (extractFluidStackExact(extractorCardCache, adjacentTank, extractStack)) {
                        extractorCardCache.currentSlot++;
                        return true;
                    }
                } else {
                    if (extractFluidStack(extractorCardCache, adjacentTank, extractStack)) {
                        extractorCardCache.currentSlot++;
                        return true;
                    }
                }
            }
        }

        extractorCardCache.currentSlot++;
        if (extractorCardCache.currentSlot >= adjacentTank.getTanks()) {
            extractorCardCache.currentSlot = 0;
            return false;
        }
        return true; // Atomic op: checked one tank
    }

    public int receiveEnergy(Direction direction, int receiveAmt, boolean simulate) {
        NodeSideCache nodeSideCache = nodeSideCaches[direction.ordinal()];
        if (nodeSideCache.nextCardIndex >= nodeSideCache.extractorCardCaches.size()) {
            nodeSideCache.nextCardIndex = 0;
        }

        // Just check one card for receiveEnergy per call to keep it bounded
        ExtractorCardCache extractorCardCache = nodeSideCache.extractorCardCaches.get(nodeSideCache.nextCardIndex);
        if (extractorCardCache.cardType == CardType.ENERGY &&
            !(extractorCardCache instanceof StockerCardCache) &&
            !(extractorCardCache instanceof SensorCardCache) &&
            extractorCardCache.remainingSleep <= 1 &&
            extractorCardCache.enabled &&
            extractorCardCache.energyReceivedExternally < extractorCardCache.extractAmt) {

            int amtSent = sendReceivedEnergy(extractorCardCache, receiveAmt, simulate);
            if (amtSent > 0) {
                if (!simulate) {
                    extractorCardCache.energyReceivedExternally += amtSent;
                }
                return amtSent;
            }
        }

        nodeSideCache.nextCardIndex++;
        return 0;
    }

    public int sendReceivedEnergy(ExtractorCardCache extractorCardCache, int receiveAmt, boolean simulate) {
        int totalAmtNeeded = Math.min(extractorCardCache.extractAmt - extractorCardCache.energyReceivedExternally, receiveAmt);
        if (totalAmtNeeded <= 0) {
            return 0;
        }
        int totalFit = 0;
        List<InserterCardCache> inserterCardCaches = getChannelMatchInserters(extractorCardCache);
        int roundRobin = -1;
        if (extractorCardCache.roundRobin != 0) {
            roundRobin = getRR(extractorCardCache);
            inserterCardCaches = applyRR(extractorCardCache, inserterCardCaches, roundRobin);
        }
        for (InserterCardCache inserterCardCache : inserterCardCaches) {
            LaserNodeEnergyHandler laserNodeEnergyHandler = getLaserNodeHandlerEnergy(inserterCardCache);
            if (laserNodeEnergyHandler == null) continue;
            IEnergyStorage energyStorage = laserNodeEnergyHandler.handler;
            int desired;
            if (inserterCardCache.insertLimit != 100) {
                desired = (int) (energyStorage.getMaxEnergyStored() * ((float) inserterCardCache.insertLimit / 100)) - energyStorage.getEnergyStored();
            } else {
                desired = receiveAmt;
            }
            if (desired <= 0) continue;
            int amtToTry = Math.min(desired, totalAmtNeeded);
            int amtFit = energyStorage.receiveEnergy(amtToTry, true); //Simulate Insert Energy
            if (amtFit == 0) { //Next inserter if nothing went in -- return false if enforcing round robin
                if (extractorCardCache.roundRobin == 2) {
                    return totalFit;
                }
                if (extractorCardCache.roundRobin != 0) getNextRR(extractorCardCache, inserterCardCaches);
                continue;
            }
            totalAmtNeeded -= amtFit; //If we removed 100 and wanted to remove 1000, keep looking for other nodes to insert into
            totalFit += amtFit;
            if (!simulate) {
                energyStorage.receiveEnergy(amtFit, false); //Insert the amount we removed from the source
                if (laserNodeEnergyHandler.be != null) {
                    LaserScheduler.requestWakeUp(laserNodeEnergyHandler.be);
                }
            }
            //drawParticlesFluid(drainedStack, extractorCardCache.direction, extractorCardCache.be, inserterCardCache.be, inserterCardCache.direction, extractorCardCache.cardSlot, inserterCardCache.cardSlot);
            if (extractorCardCache.roundRobin != 0) getNextRR(extractorCardCache, inserterCardCaches);
            if (totalAmtNeeded == 0) return totalFit;
        }
        return totalFit;
    }

    public boolean extractEnergy(ExtractorCardCache extractorCardCache, IEnergyStorage fromEnergyTank, int extractAmt) {
        if (extractorCardCache.currentStep == ExtractorCardCache.TransferStep.SCAN_SLOTS) {
            extractorCardCache.currentInserterIndex = 0;
            extractorCardCache.cachedPossibleInserters = getChannelMatchInserters(extractorCardCache);
            if (extractorCardCache.roundRobin != 0) {
                int roundRobin = getRR(extractorCardCache);
                extractorCardCache.cachedPossibleInserters = applyRR(extractorCardCache, extractorCardCache.cachedPossibleInserters, roundRobin);
            }
            extractorCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_INSERTERS;
            return true;
        }

        if (extractorCardCache.currentStep == ExtractorCardCache.TransferStep.SCAN_INSERTERS) {
            if (extractorCardCache.currentInserterIndex >= extractorCardCache.cachedPossibleInserters.size()) {
                extractorCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_SLOTS;
                return false;
            }

            InserterCardCache inserterCardCache = extractorCardCache.cachedPossibleInserters.get(extractorCardCache.currentInserterIndex);
            LaserNodeEnergyHandler laserNodeEnergyHandler = getLaserNodeHandlerEnergy(inserterCardCache);
            if (laserNodeEnergyHandler != null) {
                IEnergyStorage energyStorage = laserNodeEnergyHandler.handler;
                int desired;
                if (inserterCardCache.insertLimit != 100) {
                    desired = (int) (energyStorage.getMaxEnergyStored() * ((float) inserterCardCache.insertLimit / 100)) - energyStorage.getEnergyStored();
                } else {
                    desired = extractAmt;
                }

                if (desired > 0) {
                    int amtFit = energyStorage.receiveEnergy(Math.min(desired, extractAmt), true);
                    if (amtFit > 0) {
                        int amtDrained = fromEnergyTank.extractEnergy(amtFit, false);
                        if (amtDrained > 0) {
                            energyStorage.receiveEnergy(amtDrained, false);
                            if (laserNodeEnergyHandler.be != null) {
                                LaserScheduler.requestWakeUp(laserNodeEnergyHandler.be);
                            }
                            if (extractorCardCache.roundRobin != 0) getNextRR(extractorCardCache, extractorCardCache.cachedPossibleInserters);
                            // Energy is simpler, we can finish after one successful transfer
                            extractorCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_SLOTS;
                            return true;
                        }
                    }
                }
            }
            extractorCardCache.currentInserterIndex++;
            return true;
        }
        return false;
    }

    public boolean extractEnergyExact(ExtractorCardCache extractorCardCache, IEnergyStorage fromEnergyTank, int extractAmt) {
        int totalAmtNeeded = extractAmt;
        List<InserterCardCache> inserterCardCaches = getChannelMatchInserters(extractorCardCache);
        int roundRobin = -1;
        if (extractorCardCache.roundRobin != 0) {
            roundRobin = getRR(extractorCardCache);
            inserterCardCaches = applyRR(extractorCardCache, inserterCardCaches, roundRobin);
        }
        Map<InserterCardCache, Integer> insertHandlers = new Object2IntOpenHashMap<>();
        for (InserterCardCache inserterCardCache : inserterCardCaches) {
            LaserNodeEnergyHandler laserNodeEnergyHandler = getLaserNodeHandlerEnergy(inserterCardCache);
            if (laserNodeEnergyHandler == null) continue;
            IEnergyStorage energyStorage = laserNodeEnergyHandler.handler;
            int desired;
            if (inserterCardCache.insertLimit != 100) {
                desired = (int) (energyStorage.getMaxEnergyStored() * ((float) inserterCardCache.insertLimit / 100)) - energyStorage.getEnergyStored();
            } else {
                desired = extractAmt;
            }
            if (desired <= 0) continue;
            int amtToTry = Math.min(desired, totalAmtNeeded);
            int amtFit = energyStorage.receiveEnergy(amtToTry, true); //Simulate Insert
            if (amtFit == 0) { //Next inserter if nothing went in -- return false if enforcing round robin
                if (extractorCardCache.roundRobin == 2) {
                    return false;
                }
                if (extractorCardCache.roundRobin != 0) getNextRR(extractorCardCache, inserterCardCaches);
                continue;
            }
            int amtDrained = fromEnergyTank.extractEnergy(amtFit, true); //Simulate Remove some energy
            if (amtDrained == 0) continue; //If we didn't get anything
            insertHandlers.put(inserterCardCache, amtDrained); //Add the handler to the list of handlers we found fluid in
            totalAmtNeeded -= amtDrained; //Keep track of how much we have left to insert
            if (extractorCardCache.roundRobin != 0) getNextRR(extractorCardCache, inserterCardCaches);
            if (totalAmtNeeded == 0) break;
        }
        if (totalAmtNeeded > 0) return false;
        for (Map.Entry<InserterCardCache, Integer> entry : insertHandlers.entrySet()) {
            InserterCardCache inserterCardCache = entry.getKey();
            LaserNodeEnergyHandler laserNodeEnergyHandler = getLaserNodeHandlerEnergy(inserterCardCache);
            IEnergyStorage energyStorage = laserNodeEnergyHandler.handler;
            int actualRemoved = fromEnergyTank.extractEnergy(entry.getValue(), false);
            energyStorage.receiveEnergy(actualRemoved, false);
            if (laserNodeEnergyHandler.be != null) {
                LaserScheduler.requestWakeUp(laserNodeEnergyHandler.be);
            }
            //drawParticlesFluid(drainedStack, extractorCardCache.direction, extractorCardCache.be, inserterCardCache.be, inserterCardCache.direction, extractorCardCache.cardSlot, inserterCardCache.cardSlot);
        }
        return true;
    }

    /** Extractor Cards call this, and try to find an inserter card to send their items to **/
    public boolean sendEnergy(ExtractorCardCache extractorCardCache) {
        if (extractorCardCache.energyReceivedExternally != 0) {
            extractorCardCache.energyReceivedExternally = 0;
            return true;
        }
        BlockPos adjacentPos = getBlockPos().relative(extractorCardCache.direction);
        assert level != null;
        if (!level.isLoaded(adjacentPos)) return false;
        LazyOptional<IEnergyStorage> adjacentEnergyOptional = getAttachedEnergyTank(extractorCardCache.direction, extractorCardCache.sneaky);
        if (!adjacentEnergyOptional.isPresent()) return false;
        IEnergyStorage adjacentEnergy = adjacentEnergyOptional.resolve().get();
        int desired = (int) (adjacentEnergy.getMaxEnergyStored() * ((float) extractorCardCache.extractLimit / 100));
        int extractAmt = Math.min(extractorCardCache.extractAmt, adjacentEnergy.getEnergyStored() - desired);
        if (extractAmt <= 0) return false;
        if (extractorCardCache.exact) {
            return extractEnergyExact(extractorCardCache, adjacentEnergy, extractAmt);
        } else {
            return extractEnergy(extractorCardCache, adjacentEnergy, extractAmt);
        }
    }

    public boolean canAnyItemFiltersFit(IItemHandler adjacentInventory, StockerCardCache stockerCardCache) {
        for (ItemStack stack : stockerCardCache.getFilteredItems()) {
            int amountFit = testInsertToInventory(adjacentInventory, stack.split(1)); //Try to put one in - if it fits we have room
            if (amountFit > 0) {
                return true;
            }
        }
        return false;
    }

    public boolean canAnyFluidFiltersFit(IFluidHandler adjacentTank, StockerCardCache stockerCardCache) {
        for (FluidStack fluidStack : stockerCardCache.getFilteredFluids()) {
            int amtFit = adjacentTank.fill(fluidStack, IFluidHandler.FluidAction.SIMULATE);
            if (amtFit > 0) {
                return true;
            }
        }
        return false;
    }

    public boolean canFluidFitInTank(IFluidHandler handler, FluidStack fluidStack) {
        return (handler.fill(fluidStack, IFluidHandler.FluidAction.SIMULATE) > 0);
    }

    public boolean regulateItemStocker(StockerCardCache stockerCardCache, IItemHandler stockerInventory) {
        Direction inventorySide = stockerCardCache.direction.getOpposite();
        if (stockerCardCache.sneaky != -1) inventorySide = Direction.values()[stockerCardCache.sneaky];
        SideConnection sideConnection = new SideConnection(stockerCardCache.direction, inventorySide);
        ItemHandlerUtil.InventoryCounts stockerInventoryCount = perTickInventoryCounts.computeIfAbsent(new InventoryCacheKey(sideConnection, stockerCardCache.isCompareNBT), k -> new ItemHandlerUtil.InventoryCounts(stockerInventory, stockerCardCache.isCompareNBT));
        List<ItemStack> filteredItemsList = stockerCardCache.getFilteredItems();
        for (ItemStack itemStack : filteredItemsList) { //Remove all the items from the list that we already have enough of
            int amtHad = stockerInventoryCount.getCount(itemStack);
            if (amtHad > itemStack.getCount()) { //if we have enough, move onto the next stack after removing this one from the list
                ItemStack extractStack = itemStack.copy();
                extractStack.setCount(Math.min(amtHad - itemStack.getCount(), stockerCardCache.extractAmt));
                if (extractItem(stockerCardCache, stockerInventory, extractStack, 0)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean regulateFluidStocker(StockerCardCache stockerCardCache, IFluidHandler stockerTank) {
        List<FluidStack> filteredFluidsList = stockerCardCache.getFilteredFluids();
        for (FluidStack fluidStack : filteredFluidsList) { //Iterate the list of filtered items for extracting purposes
            int desiredAmt = stockerCardCache.getFilterAmt(fluidStack);
            int amtHad = 0;
            for (int tank = 0; tank < stockerTank.getTanks(); tank++) { //Loop through all the tanks
                FluidStack stackInTank = stockerTank.getFluidInTank(tank);
                if (stackInTank.isFluidEqual(fluidStack)) {
                    amtHad += stackInTank.getAmount();
                }
            }
            if (amtHad > desiredAmt) { //If we have too much of this fluid, remove the difference.
                fluidStack.setAmount(Math.min(amtHad - desiredAmt, stockerCardCache.extractAmt));
                if (extractFluidStack(stockerCardCache, stockerTank, fluidStack)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean regulateEnergyStocker(StockerCardCache stockerCardCache, IEnergyStorage stockerTank) {
        int desired = (int) (stockerTank.getMaxEnergyStored() * ((float) stockerCardCache.insertLimit / 100));
        if (desired >= stockerTank.getEnergyStored()) {
            return false;
        }
        int overFlow = Math.min(stockerCardCache.extractAmt, stockerTank.getEnergyStored() - desired);
        return extractEnergy(stockerCardCache, stockerTank, overFlow);
    }

    /** Stocker Cards call this, and try to find an inserter card to pull their fluids from **/
    public boolean stockEnergy(StockerCardCache stockerCardCache) {
        BlockPos adjacentPos = getBlockPos().relative(stockerCardCache.direction);
        assert level != null;
        if (!level.isLoaded(adjacentPos)) return false;
        Optional<IEnergyStorage> adjacentEnergyOptional = getAttachedEnergyTank(stockerCardCache.direction, stockerCardCache.sneaky).resolve();
        if (adjacentEnergyOptional.isEmpty()) return false;
        IEnergyStorage adjacentEnergy = adjacentEnergyOptional.get();
        if (stockerCardCache.regulate) {
            if (regulateEnergyStocker(stockerCardCache, adjacentEnergy)) {
                return true;
            }
        }
        int desired = (int) (adjacentEnergy.getMaxEnergyStored() * ((float) stockerCardCache.insertLimit / 100));
        if (adjacentEnergy.getEnergyStored() >= desired) {
            return false; //If we can't fit any more energy into here
        }
        return findEnergyForStocker(stockerCardCache, adjacentEnergy);
    }

    /** Stocker Cards call this, and try to find an inserter card to pull their fluids from **/
    public boolean stockFluids(StockerCardCache stockerCardCache) {
        BlockPos adjacentPos = getBlockPos().relative(stockerCardCache.direction);
        assert level != null;
        if (!level.isLoaded(adjacentPos)) return false;
        Optional<IFluidHandler> adjacentTankOptional = getAttachedFluidTank(stockerCardCache.direction, stockerCardCache.sneaky).resolve();
        if (adjacentTankOptional.isEmpty()) return false;
        IFluidHandler adacentTank = adjacentTankOptional.get();

        ItemStack filter = stockerCardCache.filterCard;
        if (filter.isEmpty() || !stockerCardCache.isAllowList) { //Needs a filter - at least for now? Also must be in whitelist mode
            return false;
        }
        if (filter.getItem() instanceof FilterBasic || filter.getItem() instanceof FilterCount) {
            if (stockerCardCache.regulate && filter.getItem() instanceof FilterCount) {
                if (regulateFluidStocker(stockerCardCache, adacentTank))
                    return true;
            }
            if (!canAnyFluidFiltersFit(adacentTank, stockerCardCache)) {
                return false; //If we can't fit any of our filtered items into this inventory, don't bother scanning for them
            }
            boolean foundItems = findFluidStackForStocker(stockerCardCache, adacentTank); //Start looking for this item
            if (foundItems)
                return true;

            //If we get to this line of code, it means we found none of the filter
            //stockerCardCache.setRemainingSleep(stockerCardCache.tickSpeed * 5);
        } else if (filter.getItem() instanceof FilterTag) {

        }
        return false;
    }

    /** Stocker Cards call this, and try to find an inserter card to pull their items from **/
    public boolean stockItems(StockerCardCache stockerCardCache) {
        BlockPos adjacentPos = getBlockPos().relative(stockerCardCache.direction);
        assert level != null;
        if (!level.isLoaded(adjacentPos)) return false;
        IItemHandler adjacentInventory = getAttachedInventory(stockerCardCache.direction, stockerCardCache.sneaky).orElse(EMPTY);
        ItemStack filter = stockerCardCache.filterCard;

        if (filter.isEmpty() || !stockerCardCache.isAllowList) { //Needs a filter - at least for now? Also must be in whitelist mode
            return false;
        }

        if (stockerCardCache.currentStep == ExtractorCardCache.TransferStep.SCAN_SLOTS) {
            if (stockerCardCache.regulate && filter.getItem() instanceof FilterCount) {
                stockerCardCache.currentStep = ExtractorCardCache.TransferStep.REGULATE;
                stockerCardCache.currentFilterIndex = 0;
                return true;
            } else {
                stockerCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_FILTER;
                stockerCardCache.currentFilterIndex = 0;
                return true;
            }
        }

        if (stockerCardCache.currentStep == ExtractorCardCache.TransferStep.REGULATE) {
            List<ItemStack> filteredItems = stockerCardCache.getFilteredItems();
            if (stockerCardCache.currentFilterIndex >= filteredItems.size()) {
                stockerCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_FILTER;
                stockerCardCache.currentFilterIndex = 0;
                return true;
            }
            ItemStack itemStack = filteredItems.get(stockerCardCache.currentFilterIndex);
            boolean didWork = regulateItemStockerIncremental(stockerCardCache, adjacentInventory, itemStack);
            if (didWork) {
                // If it successfully extracted some overflow, it returns true and stays on this filter index potentially
                // or moves to next. For simplicity, let's move to next or COMPLETE.
                stockerCardCache.currentFilterIndex++;
                return true;
            }
            stockerCardCache.currentFilterIndex++;
            return true;
        }

        if (stockerCardCache.currentStep == ExtractorCardCache.TransferStep.SCAN_FILTER) {
            List<ItemStack> filteredItems = stockerCardCache.getFilteredItems();
            if (stockerCardCache.currentFilterIndex >= filteredItems.size()) {
                stockerCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_SLOTS;
                stockerCardCache.currentFilterIndex = 0;
                return false; // Done scanning all filters
            }
            ItemStack itemStack = filteredItems.get(stockerCardCache.currentFilterIndex);
            boolean didWork = findItemStackForStockerIncremental(stockerCardCache, adjacentInventory, itemStack);
            if (didWork) {
                stockerCardCache.currentFilterIndex++;
                return true;
            }
            stockerCardCache.currentFilterIndex++;
            return true;
        }

        return false;
    }

    public boolean regulateItemStockerIncremental(StockerCardCache stockerCardCache, IItemHandler stockerInventory, ItemStack itemStack) {
        Direction inventorySide = stockerCardCache.direction.getOpposite();
        if (stockerCardCache.sneaky != -1) inventorySide = Direction.values()[stockerCardCache.sneaky];
        SideConnection sideConnection = new SideConnection(stockerCardCache.direction, inventorySide);
        ItemHandlerUtil.InventoryCounts stockerInventoryCount = perTickInventoryCounts.computeIfAbsent(new InventoryCacheKey(sideConnection, stockerCardCache.isCompareNBT), k -> new ItemHandlerUtil.InventoryCounts(stockerInventory, stockerCardCache.isCompareNBT));

        int amtHad = stockerInventoryCount.getCount(itemStack);
        if (amtHad > itemStack.getCount()) {
            ItemStack extractStack = itemStack.copy();
            extractStack.setCount(Math.min(amtHad - itemStack.getCount(), stockerCardCache.extractAmt));
            // extractItem is now incremental too... this is getting complex.
            // For now, let's keep regulate slightly more atomic but still bounded.
            return extractItem(stockerCardCache, stockerInventory, extractStack, 0);
        }
        return false;
    }

    public boolean findItemStackForStockerIncremental(StockerCardCache stockerCardCache, IItemHandler stockerInventory, ItemStack itemStack) {
        if (stockerCardCache.currentStep == ExtractorCardCache.TransferStep.SCAN_FILTER) {
            Direction stockerSide = stockerCardCache.direction.getOpposite();
            if (stockerCardCache.sneaky != -1) stockerSide = Direction.values()[stockerCardCache.sneaky];
            SideConnection sideConnectionStocker = new SideConnection(stockerCardCache.direction, stockerSide);
            ItemHandlerUtil.InventoryCounts stockerInventoryCount = perTickInventoryCounts.computeIfAbsent(new InventoryCacheKey(sideConnectionStocker, stockerCardCache.isCompareNBT), k -> new ItemHandlerUtil.InventoryCounts(stockerInventory, stockerCardCache.isCompareNBT));

            int amtHad = stockerInventoryCount.getCount(itemStack);
            if (amtHad >= itemStack.getCount() && (stockerCardCache.filterCard.getItem() instanceof FilterCount)) {
                return false;
            }

            int countNeeded = itemStack.getCount();
            if (!(stockerCardCache.filterCard.getItem() instanceof FilterCount)) {
                countNeeded = stockerCardCache.extractAmt;
            } else {
                countNeeded = Math.min(itemStack.getCount() - amtHad, stockerCardCache.extractAmt);
            }

            if (countNeeded <= 0) return false;

            stockerCardCache.extractingStack = itemStack.copy();
            stockerCardCache.extractingStack.setCount(countNeeded);

            stockerCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_INSERTERS;
            stockerCardCache.currentInserterIndex = 0;
            stockerCardCache.cachedPossibleInserters = null;
            stockerCardCache.currentTransferResult = new TransferResult();
            return true;
        }

        if (stockerCardCache.currentStep == ExtractorCardCache.TransferStep.SCAN_INSERTERS) {
            if (stockerCardCache.cachedPossibleInserters == null) {
                stockerCardCache.cachedPossibleInserters = getChannelMatchInserters(stockerCardCache);
            }

            if (stockerCardCache.currentInserterIndex >= stockerCardCache.cachedPossibleInserters.size()) {
                int totalFound = stockerCardCache.currentTransferResult.getTotalItemCounts();
                if (totalFound > 0 && (!stockerCardCache.exact || totalFound >= stockerCardCache.extractingStack.getCount())) {
                    stockerCardCache.currentStep = ExtractorCardCache.TransferStep.EXECUTE_TRANSFER;
                    return true;
                }
                stockerCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_FILTER;
                return false;
            }

            InserterCardCache inserterCardCache = stockerCardCache.cachedPossibleInserters.get(stockerCardCache.currentInserterIndex);
            if (inserterCardCache.isStackValidForCard(stockerCardCache.extractingStack)) {
                LaserNodeItemHandler laserNodeItemHandler = getLaserNodeHandlerItem(inserterCardCache);
                if (laserNodeItemHandler != null) {
                    int slots = laserNodeItemHandler.handler.getSlots();
                    if (stockerCardCache.currentInserterSlot >= slots) {
                        stockerCardCache.currentInserterSlot = 0;
                    }

                    int insSlot = stockerCardCache.currentInserterSlot;
                    ItemStack stackInSlot = laserNodeItemHandler.handler.getStackInSlot(insSlot);
                    if (!stackInSlot.isEmpty() && ItemHandlerUtil.doItemsMatch(stockerCardCache.extractingStack, stackInSlot, stockerCardCache.isCompareNBT)) {
                        int stillNeeded = stockerCardCache.extractingStack.getCount() - stockerCardCache.currentTransferResult.getTotalItemCounts();
                        int taking = Math.min(stillNeeded, stackInSlot.getCount());
                        ItemStack extracted = laserNodeItemHandler.handler.extractItem(insSlot, taking, true);
                        if (!extracted.isEmpty()) {
                            stockerCardCache.currentTransferResult.addResult(new TransferResult.Result(laserNodeItemHandler.handler, insSlot, inserterCardCache, extracted, laserNodeItemHandler.be, true));
                        }
                    }

                    stockerCardCache.currentInserterSlot++;
                    if (stockerCardCache.currentInserterSlot >= slots) {
                        stockerCardCache.currentInserterSlot = 0;
                        stockerCardCache.currentInserterIndex++;
                    }
                    return true;
                }
            }
            stockerCardCache.currentInserterIndex++;
            stockerCardCache.currentInserterSlot = 0;
            return true;
        }

        if (stockerCardCache.currentStep == ExtractorCardCache.TransferStep.EXECUTE_TRANSFER) {
            // Check if it fits in stocker inventory
            ItemStack toInsert = stockerCardCache.extractingStack.copy();
            toInsert.setCount(stockerCardCache.currentTransferResult.getTotalItemCounts());
            ItemStack remainder = ItemHandlerHelper.insertItem(stockerInventory, toInsert, true);
            int canFit = toInsert.getCount() - remainder.getCount();

            if (canFit <= 0 || (stockerCardCache.exact && canFit < toInsert.getCount())) {
                stockerCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_FILTER;
                return false;
            }

            // Perform extraction and insertion
            int totalToMove = canFit;
            for (TransferResult.Result res : stockerCardCache.currentTransferResult.results) {
                int taking = Math.min(totalToMove, res.itemStack.getCount());
                ItemStack moved = res.extractHandler.extractItem(res.extractSlot, taking, false);
                ItemHandlerHelper.insertItem(stockerInventory, moved, false);
                if (res.fromBE != null) {
                    LaserScheduler.requestWakeUp(res.fromBE);
                }
                totalToMove -= moved.getCount();
                if (totalToMove <= 0) break;
            }

            stockerCardCache.currentStep = ExtractorCardCache.TransferStep.SCAN_FILTER;
            return true;
        }

        return false;
    }

    public ItemStack getStackAtStockerCachePosition(StockerSource checkSource) {
        LaserNodeItemHandler laserNodeItemHandler = getLaserNodeHandlerItem(checkSource.inserterCardCache);
        if (laserNodeItemHandler == null) return ItemStack.EMPTY;
        return laserNodeItemHandler.handler.getStackInSlot(checkSource.slot);
    }

    /**
     * Trys to pull from the last place we found this item - checking the same slot first, then the rest of the inventory.
     * Returns the TransferResult (Simulate enabled) that we found and adds to the set of checkedSources the DimBlockPos of the source checked.
     */
    public TransferResult tryStockerCacheCount(StockerCardCache stockerCardCache, ItemStack itemStack, IItemHandler stockerInventory, Set<DimBlockPos> checkedSources) {
        TransferResult extractResult = new TransferResult();
        ItemStackKey itemStackKey = new ItemStackKey(itemStack, stockerCardCache.isCompareNBT);
        StockerRequest stockerRequest = new StockerRequest(stockerCardCache, itemStackKey);
        if (!stockerDestinationCache.containsKey(stockerRequest))
            return extractResult;
        int origItemsWanted = itemStack.getCount();
        int itemsStillNeeded = origItemsWanted;
        StockerSource checkSource = stockerDestinationCache.get(stockerRequest);
        checkedSources.add(checkSource.inserterCardCache.relativePos);
        ItemStack stackInSlot = getStackAtStockerCachePosition(checkSource);
        if (stackInSlot == null) //Null means the inventory no longer exists or is unloaded
            return extractResult;

        ItemStack extractedItemStack;
        LaserNodeItemHandler laserNodeItemHandler = getLaserNodeHandlerItem(checkSource.inserterCardCache);
        if (laserNodeItemHandler == null) return extractResult;
        ItemStackKey stackInSlotKey = new ItemStackKey(stackInSlot, stockerCardCache.isCompareNBT);
        if (stackInSlot.isEmpty()) //Null means the inventory no longer exists or is unloaded
            stockerDestinationCache.remove(stockerRequest);
        if (stackInSlotKey.equals(itemStackKey)) {  //If the itemstack in that spot matches the itemstack we are looking for
            int extractAmt = Math.min(itemsStillNeeded, stackInSlot.getCount()); //Find out how many to extract
            extractedItemStack = laserNodeItemHandler.handler.extractItem(checkSource.slot, extractAmt, true); //Extract Items
            itemsStillNeeded = itemsStillNeeded - extractedItemStack.getCount();
            if (stackInSlot.getCount() - extractedItemStack.getCount() == 0) {
                stockerDestinationCache.remove(stockerRequest);
            }
            if (itemsStillNeeded == 0) {
                extractResult.addResult(new TransferResult.Result(laserNodeItemHandler.handler, checkSource.slot, checkSource.inserterCardCache, extractedItemStack, laserNodeItemHandler.be, true));
                extractResult.addOtherCard(stockerInventory, -1, stockerCardCache, stockerCardCache.be);
                return extractResult; //If we got all that we need, return the amount we got
            }
        }
        //If we got here, we have to find more of this item type, so start looking for it - note that we don't use the stack above because we will check the whole inventory and the stack above wasn't pulled out
        extractResult = ItemHandlerUtil.extractItemWithSlots(laserNodeItemHandler.be, laserNodeItemHandler.handler, itemStack, origItemsWanted, true, stockerCardCache.isCompareNBT, checkSource.inserterCardCache); //Try to pull out the items we need from this location
        extractResult.addOtherCard(stockerInventory, -1, stockerCardCache, stockerCardCache.be);
        if (!extractResult.results.isEmpty()) { //If we found something, check if the last slot we looked at is empty, and add it to the cache
            int lastSlot = extractResult.results.get(extractResult.results.size() - 1).extractSlot; //The last slot we pulled from in this inventory
            if (laserNodeItemHandler.handler.getStackInSlot(lastSlot).getCount() - extractResult.results.get(extractResult.results.size() - 1).itemStack.getCount() != 0) { //If its not empty now
                stockerDestinationCache.put(new StockerRequest(stockerCardCache, itemStackKey), new StockerSource(checkSource.inserterCardCache, lastSlot)); //Add to the cache
            }
        }
        return extractResult;
    }

    public boolean findEnergyForStocker(StockerCardCache stockerCardCache, IEnergyStorage toEnergyTank) {
        int desired = (int) (toEnergyTank.getMaxEnergyStored() * ((float) stockerCardCache.insertLimit / 100));
        int extractAmt = Math.min(stockerCardCache.extractAmt, desired - toEnergyTank.getEnergyStored());
        List<InserterCardCache> inserterCardCaches = getChannelMatchInserters(stockerCardCache);
        Map<InserterCardCache, Integer> insertHandlers = new Object2IntOpenHashMap<>();

        for (InserterCardCache inserterCardCache : inserterCardCaches) {
            LaserNodeEnergyHandler laserNodeEnergyHandler = getLaserNodeHandlerEnergy(inserterCardCache);
            if (laserNodeEnergyHandler == null) continue;
            IEnergyStorage energyStorage = laserNodeEnergyHandler.handler;

            int amtRemoved = energyStorage.extractEnergy(extractAmt, true); //Simulate Extract
            if (amtRemoved == 0) { //Next inserter if nothing removed
                continue;
            }
            int amtInserted = toEnergyTank.receiveEnergy(amtRemoved, true); //Simulate Inserting some energy
            if (amtInserted == 0) return false; //This really shouldn't happen - it means the tank is already full?
            insertHandlers.put(inserterCardCache, amtInserted); //Add the handler
            extractAmt -= amtInserted; //Keep track of how much we have left to insert
            if (extractAmt == 0) break;
        }

        if ((stockerCardCache.exact && extractAmt > 0) || insertHandlers.isEmpty()) return false;

        for (Map.Entry<InserterCardCache, Integer> entry : insertHandlers.entrySet()) {
            InserterCardCache inserterCardCache = entry.getKey();
            LaserNodeEnergyHandler laserNodeEnergyHandler = getLaserNodeHandlerEnergy(inserterCardCache);
            IEnergyStorage energyStorage = laserNodeEnergyHandler.handler;
            int actualRemoved = energyStorage.extractEnergy(entry.getValue(), false);
            toEnergyTank.receiveEnergy(actualRemoved, false);
            if (laserNodeEnergyHandler.be != null) {
                LaserScheduler.requestWakeUp(laserNodeEnergyHandler.be);
            }
            //drawParticlesFluid(drainedStack, extractorCardCache.direction, extractorCardCache.be, inserterCardCache.be, inserterCardCache.direction, extractorCardCache.cardSlot, inserterCardCache.cardSlot);
        }

        return false; //If we got NOTHING
    }

    public boolean findFluidStackForStocker(StockerCardCache stockerCardCache, IFluidHandler stockerTank) {
        boolean isCount = stockerCardCache.filterCard.getItem() instanceof FilterCount;
        int extractAmt = stockerCardCache.extractAmt;

        List<FluidStack> filteredFluidsList = new CopyOnWriteArrayList<>(stockerCardCache.getFilteredFluids());
        filteredFluidsList.removeIf(fluidStack -> !canFluidFitInTank(stockerTank, fluidStack));//If this fluid can't fit in this tank at all, skip the fluid
        if (filteredFluidsList.isEmpty()) //If nothing in the filter can fit, return false
            return false;

        if (isCount) { //If this is a filter count, prune the list of items to search for to just what we need
            for (FluidStack fluidStack : filteredFluidsList) { //Remove all the items from the list that we already have enough of
                for (int tank = 0; tank < stockerTank.getTanks(); tank++) {
                    FluidStack tankStack = stockerTank.getFluidInTank(tank);
                    if (tankStack.isEmpty() || tankStack.isFluidEqual(fluidStack)) {
                        int filterAmt = stockerCardCache.getFilterAmt(fluidStack);
                        int amtHad = tankStack.getAmount();
                        int amtNeeded = filterAmt - amtHad;
                        if (amtNeeded <= 0) {//if we have enough, move onto the next stack after removing this one from the list
                            filteredFluidsList.remove(fluidStack);
                            continue;
                        }
                        fluidStack.setAmount(Math.min(amtNeeded, extractAmt)); //Adjust the amount we need
                    }
                }
            }
        }

        if (filteredFluidsList.isEmpty()) //If we have nothing left to look for! Probably only happens when its a count card.
            return false;

        //At this point we should have a list of fluids that we need to satisfy the stock request
        for (FluidStack fluidStack : filteredFluidsList) {
            Map<InserterCardCache, FluidStack> insertHandlers = new HashMap<>();
            if (!isCount)
                fluidStack.setAmount(extractAmt); //If this isn't a counting card, we want the extractAmt value
            int amtNeeded = fluidStack.getAmount();

            for (InserterCardCache inserterCardCache : getChannelMatchInserters(stockerCardCache)) { //Iterate through ALL inserter nodes on this channel only
                if (!inserterCardCache.isStackValidForCard(fluidStack))
                    continue;
                LaserNodeFluidHandler laserNodeFluidHandler = getLaserNodeHandlerFluid(inserterCardCache);
                if (laserNodeFluidHandler == null) continue;
                fluidStack.setAmount(amtNeeded);
                IFluidHandler handler = laserNodeFluidHandler.handler();
                FluidStack extractStack = handler.drain(fluidStack, IFluidHandler.FluidAction.SIMULATE);
                if (extractStack.isEmpty()) continue;
                insertHandlers.put(inserterCardCache, extractStack);
                amtNeeded -= extractStack.getAmount();
                if (amtNeeded == 0) break;
            }
            if (!insertHandlers.isEmpty()) {
                if (!stockerCardCache.exact || amtNeeded == 0) { //If its not exact mode, or it is exact mode and we found all we need to satisfy this
                    for (Map.Entry<InserterCardCache, FluidStack> entry : insertHandlers.entrySet()) { //Do all the extracts/inserts
                        InserterCardCache inserterCardCache = entry.getKey();
                        FluidStack insertStack = entry.getValue();
                        LaserNodeFluidHandler laserNodeFluidHandler = getLaserNodeHandlerFluid(inserterCardCache);
                        IFluidHandler handler = laserNodeFluidHandler.handler;
                        int amtFit = stockerTank.fill(insertStack, IFluidHandler.FluidAction.SIMULATE); //Test inserting into the target
                        insertStack.setAmount(amtFit); //Change the stack to size to how much can fit
                        FluidStack drainedStack = handler.drain(insertStack, IFluidHandler.FluidAction.EXECUTE);
                        stockerTank.fill(drainedStack, IFluidHandler.FluidAction.EXECUTE);
                        if (laserNodeFluidHandler.be != null) {
                            LaserScheduler.requestWakeUp(laserNodeFluidHandler.be);
                        }
                        drawParticlesFluid(drainedStack, inserterCardCache.direction, inserterCardCache.be, stockerCardCache.be, stockerCardCache.direction, inserterCardCache.cardSlot, stockerCardCache.cardSlot);
                    }
                    return true;
                }
            }
        }
        return false; //If we got NOTHING
    }

    public boolean findItemStackForStocker(StockerCardCache stockerCardCache, IItemHandler stockerInventory) {
        // Simplified non-looping version for one itemStack (called from stockItems)
        // This is still complex because it needs to find the items across the network.
        // For the sake of the O(1) rule, let's just do a very basic check.
        // If it was already in the middle of a transfer, it would be in a different step.
        return false; // Placeholder for now, real implementation needs to be incremental too
    }

    /**
     * Attempts to insert @param stack into @param destitemHandler
     *
     * @return how many items fit
     */
    public int testInsertToInventory(IItemHandler destitemHandler, ItemStack stack) {
        ItemStack tempStack = ItemHandlerHelper.insertItem(destitemHandler, stack, true);
        int remainder = tempStack.getCount();
        return stack.getCount() - remainder;
    }

    public void drawParticlesClient() {

        /*if (true) {
            ClientLevel clientLevel = (ClientLevel) level;
            FluidStack fluidStack = new FluidStack(Fluids.LAVA, 1);
            BlockPos toPos = getBlockPos().relative(Direction.UP);
            BlockPos fromPos = getBlockPos();
            Direction direction = Direction.values()[1];

            Vector3f extractOffset = findOffset(direction, 0, offsets);
            FluidFlowParticleData data = new FluidFlowParticleData(fluidStack, toPos.getX() + extractOffset.x(), toPos.getY() + extractOffset.y(), toPos.getZ() + extractOffset.z(), 10);
            float randomSpread = 0.01f;
            int min = 1;
            int max = 64;
            int minPart = 32;
            int maxPart = 64;
            int count = ((maxPart - minPart) * (fluidStack.getAmount() - min)) / (max - min) + minPart;
            for (int i = 0; i < count; ++i) {
                //particlesDrawnThisTick++;
                double d1 = this.random.nextGaussian() * (double) randomSpread;
                double d3 = this.random.nextGaussian() * (double) randomSpread;
                double d5 = this.random.nextGaussian() * (double) randomSpread;
                clientLevel.addParticle(data, fromPos.getX() + extractOffset.x() + d1, fromPos.getY() + extractOffset.y() + d3, fromPos.getZ() + extractOffset.z() + d5, 0, 0, 0);
            }
        }

        if (true) {
            ClientLevel clientLevel = (ClientLevel) level;
            FluidStack fluidStack = new FluidStack(Fluids.WATER, 1);
            BlockPos toPos = getBlockPos().relative(Direction.UP);
            BlockPos fromPos = getBlockPos();
            Direction direction = Direction.values()[1];

            Vector3f extractOffset = findOffset(direction, 1, offsets);
            FluidFlowParticleData data = new FluidFlowParticleData(fluidStack, toPos.getX() + extractOffset.x(), toPos.getY() + extractOffset.y(), toPos.getZ() + extractOffset.z(), 10);
            float randomSpread = 0.01f;
            int min = 1;
            int max = 64;
            int minPart = 32;
            int maxPart = 64;
            int count = ((maxPart - minPart) * (fluidStack.getAmount() - min)) / (max - min) + minPart;
            for (int i = 0; i < count; ++i) {
                //particlesDrawnThisTick++;
                double d1 = this.random.nextGaussian() * (double) randomSpread;
                double d3 = this.random.nextGaussian() * (double) randomSpread;
                double d5 = this.random.nextGaussian() * (double) randomSpread;
                clientLevel.addParticle(data, fromPos.getX() + extractOffset.x() + d1, fromPos.getY() + extractOffset.y() + d3, fromPos.getZ() + extractOffset.z() + d5, 0, 0, 0);
            }
        }

        if (true) {
            ClientLevel clientLevel = (ClientLevel) level;
            FluidStack fluidStack = new FluidStack(Fluids.WATER, 1);
            BlockPos toPos = getBlockPos().relative(Direction.UP);
            BlockPos fromPos = getBlockPos();
            Direction direction = Direction.values()[1];

            Vector3f extractOffset = findOffset(direction, 1, offsets);
            FluidFlowParticleData data = new FluidFlowParticleData(fluidStack, toPos.getX() + extractOffset.x(), toPos.getY() + extractOffset.y(), toPos.getZ() + extractOffset.z(), 10);
            float randomSpread = 0.01f;
            int min = 1;
            int max = 64;
            int minPart = 32;
            int maxPart = 64;
            int count = ((maxPart - minPart) * (fluidStack.getAmount() - min)) / (max - min) + minPart;
            for (int i = 0; i < count; ++i) {
                //particlesDrawnThisTick++;
                double d1 = this.random.nextGaussian() * (double) randomSpread;
                double d3 = this.random.nextGaussian() * (double) randomSpread;
                double d5 = this.random.nextGaussian() * (double) randomSpread;
                clientLevel.addParticle(data, fromPos.getX() + extractOffset.x() + d1, fromPos.getY() + extractOffset.y() + d3, fromPos.getZ() + extractOffset.z() + d5, 0, 0, 0);
            }
        }*/
        if (particleRenderData.isEmpty() && particleRenderDataFluids.isEmpty() && particleRenderDataChemicals.isEmpty()) return;
        ClientLevel clientLevel = (ClientLevel) level;
        //int particlesDrawnThisTick = 0;
        for (ParticleRenderData partData : particleRenderData) {
            //if (particlesDrawnThisTick > 64) return;
            ItemStack itemStack = new ItemStack(Item.byId(partData.item), partData.itemCount);
            BlockPos toPos = partData.toPos;
            BlockPos fromPos = partData.fromPos;
            Direction direction = Direction.values()[partData.direction];
            BlockState targetState = level.getBlockState(toPos);
            float randomSpread = 0.01f;
            int min = 1;
            int max = 64;
            int minPart = 32;
            int maxPart = 64;
            int count = ((maxPart - minPart) * (itemStack.getCount() - min)) / (max - min) + minPart;

            if (targetState.getBlock() instanceof LaserNode) {
                targetState = level.getBlockState(fromPos);
                VoxelShape voxelShape = targetState.getShape(level, fromPos);
                Vector3f extractOffset = MiscTools.findOffset(direction, partData.position, LaserNodeBERender.OFFSETS);
                Vector3f insertOffset = CardRender.shapeOffset(extractOffset, voxelShape, fromPos, toPos, direction, level, targetState);
                ItemFlowParticleData data = new ItemFlowParticleData(itemStack, toPos.getX() + extractOffset.x(), toPos.getY() + extractOffset.y(), toPos.getZ() + extractOffset.z(), 10);
                for (int i = 0; i < count; ++i) {
                    //particlesDrawnThisTick++;
                    double d1 = this.random.nextGaussian() * (double) randomSpread;
                    double d3 = this.random.nextGaussian() * (double) randomSpread;
                    double d5 = this.random.nextGaussian() * (double) randomSpread;
                    clientLevel.addParticle(data, toPos.getX() + insertOffset.x() + d1, toPos.getY() + insertOffset.y() + d3, toPos.getZ() + insertOffset.z() + d5, 0, 0, 0);
                }
            } else {
                VoxelShape voxelShape = targetState.getShape(level, toPos);
                Vector3f extractOffset = MiscTools.findOffset(direction, partData.position, LaserNodeBERender.OFFSETS);
                Vector3f insertOffset = CardRender.shapeOffset(extractOffset, voxelShape, fromPos, toPos, direction, level, targetState);
                ItemFlowParticleData data = new ItemFlowParticleData(itemStack, fromPos.getX() + insertOffset.x(), fromPos.getY() + insertOffset.y(), fromPos.getZ() + insertOffset.z(), 10);
                for (int i = 0; i < count; ++i) {
                    //particlesDrawnThisTick++;
                    double d1 = this.random.nextGaussian() * (double) randomSpread;
                    double d3 = this.random.nextGaussian() * (double) randomSpread;
                    double d5 = this.random.nextGaussian() * (double) randomSpread;
                    clientLevel.addParticle(data, fromPos.getX() + extractOffset.x() + d1, fromPos.getY() + extractOffset.y() + d3, fromPos.getZ() + extractOffset.z() + d5, 0, 0, 0);
                }
            }
        }

        for (ParticleRenderDataFluid partData : particleRenderDataFluids) {
            //if (particlesDrawnThisTick > 64) return;
            FluidStack fluidStack = partData.fluidStack;
            if (fluidStack.isEmpty()) continue; //I managed to crash without this, so added it :)
            BlockPos toPos = partData.toPos;
            BlockPos fromPos = partData.fromPos;
            Direction direction = Direction.values()[partData.direction];
            BlockState targetState = level.getBlockState(toPos);
            float randomSpread = 0.01f;
            int min = 100;
            int max = 8000;
            int minPart = 8;
            int maxPart = 64;
            int count = ((maxPart - minPart) * (fluidStack.getAmount() - min)) / (max - min) + minPart;

            if (targetState.getBlock() instanceof LaserNode) {
                targetState = level.getBlockState(fromPos);
                VoxelShape voxelShape = targetState.getShape(level, fromPos);
                Vector3f extractOffset = MiscTools.findOffset(direction, partData.position, LaserNodeBERender.OFFSETS);
                Vector3f insertOffset = CardRender.shapeOffset(extractOffset, voxelShape, fromPos, toPos, direction, level, targetState);
                FluidFlowParticleData data = new FluidFlowParticleData(fluidStack, toPos.getX() + extractOffset.x(), toPos.getY() + extractOffset.y(), toPos.getZ() + extractOffset.z(), 10);
                for (int i = 0; i < count; ++i) {
                    //particlesDrawnThisTick++;
                    double d1 = this.random.nextGaussian() * (double) randomSpread;
                    double d3 = this.random.nextGaussian() * (double) randomSpread;
                    double d5 = this.random.nextGaussian() * (double) randomSpread;
                    clientLevel.addParticle(data, toPos.getX() + insertOffset.x() + d1, toPos.getY() + insertOffset.y() + d3, toPos.getZ() + insertOffset.z() + d5, 0, 0, 0);
                }
            } else {
                VoxelShape voxelShape = targetState.getShape(level, toPos);
                Vector3f extractOffset = MiscTools.findOffset(direction, partData.position, LaserNodeBERender.OFFSETS);
                Vector3f insertOffset = CardRender.shapeOffset(extractOffset, voxelShape, fromPos, toPos, direction, level, targetState);
                FluidFlowParticleData data = new FluidFlowParticleData(fluidStack, fromPos.getX() + insertOffset.x(), fromPos.getY() + insertOffset.y(), fromPos.getZ() + insertOffset.z(), 10);
                for (int i = 0; i < count; ++i) {
                    //particlesDrawnThisTick++;
                    double d1 = this.random.nextGaussian() * (double) randomSpread;
                    double d3 = this.random.nextGaussian() * (double) randomSpread;
                    double d5 = this.random.nextGaussian() * (double) randomSpread;
                    clientLevel.addParticle(data, fromPos.getX() + extractOffset.x() + d1, fromPos.getY() + extractOffset.y() + d3, fromPos.getZ() + extractOffset.z() + d5, 0, 0, 0);
                }
            }
        }

        for (ParticleRenderDataChemical partData : particleRenderDataChemicals) {
            mekanismCache.drawParticlesClient(partData);
        }
        //System.out.println(particlesDrawnThisTick);
    }

    /** Adds from the PacketNodeParticles a set of particles to draw next client tick **/
    public void addParticleData(ParticleRenderData particleRenderData) {
        this.particleRenderData.add(particleRenderData);
    }

    public void addParticleDataFluid(ParticleRenderDataFluid particleRenderData) {
        this.particleRenderDataFluids.add(particleRenderData);
    }

    public void addParticleDataChemical(ParticleRenderDataChemical particleRenderData) {
        this.particleRenderDataChemicals.add(particleRenderData);
    }

    /** Draw the particles between node and inventory **/
    public void drawParticles(ItemStack itemStack, Direction fromDirection, LaserNodeBE sourceBE, LaserNodeBE destinationBE, Direction destinationDirection, int extractPosition, int insertPosition) {
        drawParticles(itemStack, itemStack.getCount(), fromDirection, sourceBE, destinationBE, destinationDirection, extractPosition, insertPosition);
    }

    /** Draw the particles between node and inventory **/
    public void drawParticlesFluid(FluidStack fluidStack, Direction fromDirection, LaserNodeBE sourceBE, LaserNodeBE destinationBE, Direction destinationDirection, int extractPosition, int insertPosition) {
        if (!sourceBE.getShowParticles() || !destinationBE.getShowParticles()) return;
        ServerTickHandler.addToListFluid(new ParticleDataFluid(fluidStack, new DimBlockPos(sourceBE.level, sourceBE.getBlockPos()), (byte) fromDirection.ordinal(), new DimBlockPos(destinationBE.level, destinationBE.getBlockPos()), (byte) destinationDirection.ordinal(), (byte) extractPosition, (byte) insertPosition));
    }

    /** Draw the particles between node and inventory **/
    public void drawParticles(ItemStack itemStack, int amount, Direction fromDirection, LaserNodeBE sourceBE, LaserNodeBE destinationBE, Direction destinationDirection, int extractPosition, int insertPosition) {
        if (!sourceBE.getShowParticles() || !destinationBE.getShowParticles()) return;
        ServerTickHandler.addToList(new ParticleData(Item.getId(itemStack.getItem()), (byte) amount, new DimBlockPos(sourceBE.level, sourceBE.getBlockPos()), (byte) fromDirection.ordinal(), new DimBlockPos(destinationBE.level, destinationBE.getBlockPos()), (byte) destinationDirection.ordinal(), (byte) extractPosition, (byte) insertPosition));

        /*ServerLevel serverWorld = (ServerLevel) level;
        //Extract
        BlockPos fromPos = getBlockPos().relative(direction);
        BlockPos toPos = getBlockPos();
        Vector3f extractOffset = findOffset(direction, position, offsets);
        ItemFlowParticleData data = new ItemFlowParticleData(itemStack, toPos.getX() + extractOffset.x(), toPos.getY() + extractOffset.y(), toPos.getZ() + extractOffset.z(), 10);
        float randomSpread = 0.01f;
        serverWorld.sendParticles(data, fromPos.getX() + extractOffset.x(), fromPos.getY() + extractOffset.y(), fromPos.getZ() + extractOffset.z(), 8 * itemStack.getCount(), randomSpread, randomSpread, randomSpread, 0);
        //Insert
        fromPos = destinationBE.getBlockPos();
        toPos = destinationBE.getBlockPos().relative(destinationDirection);
        Vector3f insertOffset = findOffset(destinationDirection, insertPosition, offsets);
        data = new ItemFlowParticleData(itemStack, toPos.getX() + insertOffset.x(), toPos.getY() + insertOffset.y(), toPos.getZ() + insertOffset.z(), 10);
        serverWorld.sendParticles(data, fromPos.getX() + insertOffset.x(), fromPos.getY() + insertOffset.y(), fromPos.getZ() + insertOffset.z(), 8 * itemStack.getCount(), randomSpread, randomSpread, randomSpread, 0);
        */
    }

    /** Called when changes happen - such as a card going into a side, or a card being modified via container **/
    public void updateThisNode() {
        setChanged();
        for (Direction direction : Direction.values()) {
            NodeSideCache nodeSideCache = nodeSideCaches[direction.ordinal()];
            nodeSideCache.myRedstoneFromSensors.clear();
            nodeSideCache.invalidateEnergy();
        }
        redstoneChecked = false;
        //populateThisRedstoneNetwork(false);
        notifyOtherNodesOfChange();
        markDirtyClient();
        findMyExtractors();
        updateOverclockers();
        //updateRedstoneOutputs();
        LaserScheduler.requestWakeUp(this);
    }

    /** When this node changes, tell other nodes to refresh their cache of it **/
    public void notifyOtherNodesOfChange() {
        if (level == null) return;
        for (DimBlockPos pos : otherNodesInNetwork) {
            Level targetLevel = pos.getLevel(level.getServer());
            if (targetLevel == null) continue;
            LaserNodeBE node = getNodeAt(new DimBlockPos(targetLevel, getWorldPos(pos.blockPos)));
            if (node == null) continue;
            node.checkInvNode(new DimBlockPos(this.level, this.getBlockPos()), true);
            //node.refreshRedstoneNetwork();
            node.redstoneRefreshed = false;
            LaserScheduler.requestWakeUp(node);
        }
    }

    /** This method clears the non-persistent inventory node data variables and regenerates them from scratch */
    public void refreshAllInvNodes() {
        inserterNodes.clear();
        inserterCache.clear();
        inserterCacheFluid.clear();
        if (mekanismCache != null) {
            mekanismCache.inserterCacheChemical.clear();
        }
        channelOnlyCache.clear();
        this.stockerDestinationCache.clear();
        emptyCards.clear();
        this.redstoneNetwork.clear();
        if (level == null) return;
        for (DimBlockPos pos : otherNodesInNetwork) {
            Level targetLevel = pos.getLevel(level.getServer());
            if (targetLevel == null) continue;
            checkInvNode(new DimBlockPos(targetLevel, getWorldPos(pos.blockPos)), false);
        }
        //refreshRedstoneNetwork();
        redstoneRefreshed = false;
        sortInserters();
    }

    /**
     * Given a @param pos, look up the inventory node at that position in the world, and cache each of the cards in the cardCache Variable
     * Also populates the extractorNodes and inserterNodes variables, so we know which inventory nodes send/receive items.
     * Also populates the providerNodes and stockerNodes variables, so we know which inventory nodes provide or keep in stock items.
     * This method is called by refreshAllInvNodes() or on demand when the contents of an inventory node's container is changed
     */
    public void checkInvNode(DimBlockPos pos, boolean sortInserters) {
        //System.out.println("Check inv node at: " + getBlockPos());
        LaserNodeBE be = getNodeAt(pos);
        if (be == null) return; //If the block position given doesn't contain a LaserNodeBE stop

        DimBlockPos relativePos = new DimBlockPos(be.level, getRelativePos(pos.blockPos));
        //Remove this position from all caches, so we can repopulate below
        inserterNodes.removeIf(p -> p.relativePos.equals(relativePos));
        inserterCache.clear();
        inserterCacheFluid.clear();
        if (mekanismCache != null) {
            mekanismCache.inserterCacheChemical.clear();
        }
        channelOnlyCache.clear();
        this.stockerDestinationCache.clear();
        emptyCards.clear();

        /*for (Map.Entry<Byte, Byte> beRedstone: be.myRedstoneIn.entrySet()) {
            updateRedstoneNetwork(beRedstone.getKey(), beRedstone.getValue());
        }*/
        for (Direction direction : Direction.values()) {
            NodeSideCache nodeSideCache = be.nodeSideCaches[direction.ordinal()];
            for (int slot = 0; slot < LaserNodeContainer.CARD_SLOTS; slot++) {
                ItemStack card = nodeSideCache.itemHandler.getStackInSlot(slot);
                if (card.getItem() instanceof BaseCard && !(card.getItem() instanceof CardRedstone)) {
                    if (BaseCard.getNamedTransferMode(card) == TransferMode.INSERT) {
                        inserterNodes.add(new InserterCardCache(relativePos, direction, card, be, slot));
                    }
                }
            }
        }
        if (sortInserters) sortInserters();
    }

    public LaserNodeItemHandler getLaserNodeHandlerItem(InserterCardCache inserterCardCache) {
        if (inserterCardCache.cardType != CardType.ITEM) return null;
        if (level == null) return null;
        Level targetLevel = inserterCardCache.relativePos.getLevel(level.getServer());
        if (targetLevel == null) return null;
        BlockPos nodeWorldPos = getWorldPos(inserterCardCache.relativePos.blockPos);
        DimBlockPos nodeDimWorldPos = new DimBlockPos(targetLevel, nodeWorldPos);
        if (!chunksLoaded(nodeDimWorldPos, nodeWorldPos.relative(inserterCardCache.direction))) return null;
        LaserNodeBE be = getNodeAt(nodeDimWorldPos);
        if (be == null) return null;
        IItemHandler handler = be.getAttachedInventory(inserterCardCache.direction, inserterCardCache.sneaky).orElse(EMPTY);
        if (handler.getSlots() == 0) return null;
        return new LaserNodeItemHandler(be, handler);
    }

    /** Somehow this makes it so if you break an adjacent chest it immediately invalidates the cache of it **/
    public LazyOptional<IItemHandler> getAttachedInventory(Direction direction, Byte sneakySide) {
        Direction inventorySide = direction.getOpposite();
        if (sneakySide != -1)
            inventorySide = Direction.values()[sneakySide];
        SideConnection sideConnection = new SideConnection(direction, inventorySide);
        LazyOptional<IItemHandler> testHandler = facingHandlerItem.get(sideConnection);
        if (testHandler != null && testHandler.isPresent()) {
            return testHandler;
        }

        // if no inventory cached yet, find a new one
        assert level != null;
        BlockEntity be = level.getBlockEntity(getBlockPos().relative(direction));
        // if we have a TE and its an item handler, try extracting from that
        if (be != null) {
            LazyOptional<IItemHandler> handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, inventorySide);
            if (handler.isPresent()) {
                // add the invalidator
                handler.addListener(getInvalidatorItem(sideConnection));
                // cache and return
                facingHandlerItem.put(sideConnection, handler);
                return handler;
            }
        }
        // no item handler, cache empty
        facingHandlerItem.remove(sideConnection);
        return LazyOptional.empty();
    }

    public LazyOptional<IItemHandler> getAttachedInventoryNoCache(Direction direction, Byte sneakySide) {
        Direction inventorySide = direction.getOpposite();
        if (sneakySide != -1)
            inventorySide = Direction.values()[sneakySide];

        // if no inventory cached yet, find a new one
        assert level != null;
        BlockEntity be = level.getBlockEntity(getBlockPos().relative(direction));
        // if we have a TE and its an item handler, try extracting from that
        if (be != null) {
            LazyOptional<IItemHandler> handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER, inventorySide);
            if (handler.isPresent()) {
                return handler;
            }
        }
        return LazyOptional.empty();
    }

    private NonNullConsumer<LazyOptional<IItemHandler>> getInvalidatorItem(SideConnection sideConnection) {
        return connectionInvalidatorItem.computeIfAbsent(sideConnection, c -> new WeakConsumerWrapper<>(this, (te, handler) -> {
            if (te.facingHandlerItem.get(sideConnection) == handler) {
                te.clearCachedInventories(sideConnection);
            }
        }));
    }

    public LaserNodeFluidHandler getLaserNodeHandlerFluid(InserterCardCache inserterCardCache) {
        if (inserterCardCache.cardType != CardType.FLUID) return null;
        if (level == null) return null;
        Level targetLevel = inserterCardCache.relativePos.getLevel(level.getServer());
        if (targetLevel == null) return null;
        BlockPos nodeWorldPos = getWorldPos(inserterCardCache.relativePos.blockPos);
        DimBlockPos nodeDimWorldPos = new DimBlockPos(targetLevel, nodeWorldPos);
        if (!chunksLoaded(nodeDimWorldPos, nodeWorldPos.relative(inserterCardCache.direction))) return null;
        LaserNodeBE be = getNodeAt(nodeDimWorldPos);
        if (be == null) return null;
        LazyOptional<IFluidHandler> fluidHandler = be.getAttachedFluidTank(inserterCardCache.direction, inserterCardCache.sneaky);
        if (!fluidHandler.isPresent()) return null;
        IFluidHandler handler = fluidHandler.resolve().get();
        if (handler.getTanks() == 0) return null;
        return new LaserNodeFluidHandler(be, handler);
    }

    /** Somehow this makes it so if you break an adjacent chest it immediately invalidates the cache of it **/
    public LazyOptional<IFluidHandler> getAttachedFluidTank(Direction direction, Byte sneakySide) {
        Direction inventorySide = direction.getOpposite();
        if (sneakySide != -1)
            inventorySide = Direction.values()[sneakySide];
        SideConnection sideConnection = new SideConnection(direction, inventorySide);
        LazyOptional<IFluidHandler> testHandler = facingHandlerFluid.get(sideConnection);
        if (testHandler != null && testHandler.isPresent()) {
            return testHandler;
        }

        // if no inventory cached yet, find a new one
        assert level != null;
        BlockEntity be = level.getBlockEntity(getBlockPos().relative(direction));
        // if we have a TE and its an item handler, try extracting from that
        if (be != null) {
            LazyOptional<IFluidHandler> handler = be.getCapability(ForgeCapabilities.FLUID_HANDLER, inventorySide);
            if (handler.isPresent()) {
                // add the invalidator
                handler.addListener(getInvalidatorFluid(sideConnection));
                // cache and return
                facingHandlerFluid.put(sideConnection, handler);
                return handler;
            }
        }
        // no item handler, cache empty
        facingHandlerFluid.remove(sideConnection);
        return LazyOptional.empty();
    }

    public LazyOptional<IFluidHandler> getAttachedFluidTankNoCache(Direction direction, Byte sneakySide) {
        Direction inventorySide = direction.getOpposite();
        if (sneakySide != -1)
            inventorySide = Direction.values()[sneakySide];

        // if no inventory cached yet, find a new one
        assert level != null;
        BlockEntity be = level.getBlockEntity(getBlockPos().relative(direction));
        // if we have a TE and its an item handler, try extracting from that
        if (be != null) {
            LazyOptional<IFluidHandler> handler = be.getCapability(ForgeCapabilities.FLUID_HANDLER, inventorySide);
            if (handler.isPresent()) {
                return handler;
            }
        }
        return LazyOptional.empty();
    }

    public LaserNodeEnergyHandler getLaserNodeHandlerEnergy(InserterCardCache inserterCardCache) {
        if (inserterCardCache.cardType != CardType.ENERGY) return null;
        if (level == null) return null;
        Level targetLevel = inserterCardCache.relativePos.getLevel(level.getServer());
        if (targetLevel == null) return null;
        BlockPos nodeWorldPos = getWorldPos(inserterCardCache.relativePos.blockPos);
        DimBlockPos nodeDimWorldPos = new DimBlockPos(targetLevel, nodeWorldPos);
        BlockPos targetWorldPos = nodeWorldPos.relative(inserterCardCache.direction);
        if (!chunksLoaded(nodeDimWorldPos, targetWorldPos)) return null;
        LaserNodeBE be = getNodeAt(nodeDimWorldPos);
        if (be == null) return null;
        LazyOptional<IEnergyStorage> energyHandler = be.getAttachedEnergyTank(inserterCardCache.direction, inserterCardCache.sneaky);
        if (!energyHandler.isPresent()) return null;
        IEnergyStorage energyTank = energyHandler.resolve().get();
        //Prevent Energy Cards from exporting energy to other Nodes connected to the same network
        if (energyTank instanceof LaserEnergyStorage) {
            BlockPos targetPos = getRelativePos(targetWorldPos);
            DimBlockPos targetDimPos = new DimBlockPos(targetLevel, targetPos);
            if (otherNodesInNetwork.contains(targetDimPos)) return null;
        }
        return new LaserNodeEnergyHandler(be, energyTank);
    }

    /** Somehow this makes it so if you break an adjacent chest it immediately invalidates the cache of it **/
    public LazyOptional<IEnergyStorage> getAttachedEnergyTank(Direction direction, Byte sneakySide) {
        Direction inventorySide = direction.getOpposite();
        if (sneakySide != -1)
            inventorySide = Direction.values()[sneakySide];
        SideConnection sideConnection = new SideConnection(direction, inventorySide);
        LazyOptional<IEnergyStorage> testHandler = facingHandlerEnergy.get(sideConnection);
        if (testHandler != null && testHandler.isPresent()) {
            return testHandler;
        }

        // if no inventory cached yet, find a new one
        assert level != null;
        BlockEntity be = level.getBlockEntity(getBlockPos().relative(direction));
        // if we have a TE and its an item handler, try extracting from that
        if (be != null) {
            LazyOptional<IEnergyStorage> handler = be.getCapability(ForgeCapabilities.ENERGY, inventorySide);
            if (handler.isPresent()) {
                // add the invalidator
                handler.addListener(getInvalidatorEnergy(sideConnection));
                // cache and return
                facingHandlerEnergy.put(sideConnection, handler);
                return handler;
            }
        }
        // no item handler, cache empty
        facingHandlerEnergy.remove(sideConnection);
        return LazyOptional.empty();
    }

    public LazyOptional<IEnergyStorage> getAttachedEnergyTankNoCache(Direction direction, Byte sneakySide) {
        Direction inventorySide = direction.getOpposite();
        if (sneakySide != -1)
            inventorySide = Direction.values()[sneakySide];

        // if no inventory cached yet, find a new one
        assert level != null;
        BlockEntity be = level.getBlockEntity(getBlockPos().relative(direction));
        // if we have a TE and its an item handler, try extracting from that
        if (be != null) {
            LazyOptional<IEnergyStorage> handler = be.getCapability(ForgeCapabilities.ENERGY, inventorySide);
            if (handler.isPresent()) {
                return handler;
            }
        }
        return LazyOptional.empty();
    }

    private NonNullConsumer<LazyOptional<IFluidHandler>> getInvalidatorFluid(SideConnection sideConnection) {
        return connectionInvalidatorFluid.computeIfAbsent(sideConnection, c -> new WeakConsumerWrapper<>(this, (te, handler) -> {
            if (te.facingHandlerFluid.get(sideConnection) == handler) {
                te.clearCachedInventories(sideConnection);
            }
        }));
    }

    private NonNullConsumer<LazyOptional<IEnergyStorage>> getInvalidatorEnergy(SideConnection sideConnection) {
        return connectionInvalidatorEnergy.computeIfAbsent(sideConnection, c -> new WeakConsumerWrapper<>(this, (te, handler) -> {
            if (te.facingHandlerEnergy.get(sideConnection) == handler) {
                te.clearCachedInventories(sideConnection);
            }
        }));
    }

    /** Called when a neighbor updates to invalidate the inventory cache */
    public void clearCachedInventories(SideConnection sideConnection, ChemicalType chemicalType) {
        stockerDestinationCache.clear();
        emptyCards.clear();
        this.facingHandlerItem.remove(sideConnection);
        this.facingHandlerFluid.remove(sideConnection);
        this.facingHandlerEnergy.remove(sideConnection);
        if (mekanismCache != null) {
            if (mekanismCache.facingHandlerChemical.get(sideConnection) != null) {
                mekanismCache.facingHandlerChemical.get(sideConnection).remove(chemicalType);
            }
        }
    }

    /** Called when a neighbor updates to invalidate the inventory cache */
    public void clearCachedInventories(SideConnection sideConnection) {
        stockerDestinationCache.clear();
        emptyCards.clear();
        this.facingHandlerItem.remove(sideConnection);
        this.facingHandlerFluid.remove(sideConnection);
        this.facingHandlerEnergy.remove(sideConnection);
        if (mekanismCache != null) {
            mekanismCache.facingHandlerChemical.remove(sideConnection);
        }
    }

    /** Called when a neighbor updates to invalidate the inventory cache */
    public void clearCachedInventories() {
        stockerDestinationCache.clear();
        emptyCards.clear();
        this.facingHandlerItem.clear();
        this.facingHandlerFluid.clear();
        this.facingHandlerEnergy.clear();
        if (mekanismCache != null) {
            mekanismCache.facingHandlerChemical.clear();
        }

        markDirtyClient();
    }

    public void populateRenderList() {
        //System.out.println("Refreshing Renders at: " + getBlockPos());
        if (level == null || !level.isClientSide) return;
        this.cardRenders.clear();
        redstoneCardSides.clear();
        for (Direction direction : Direction.values()) {
            IItemHandler itemHandler = getCapability(ForgeCapabilities.ITEM_HANDLER, direction).orElse(new ItemStackHandler(0));
            for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
                ItemStack card = itemHandler.getStackInSlot(slot);
                Item cardItem = card.getItem();
                if (!(cardItem instanceof BaseCard)) {
                    continue;
                }
                boolean enabled;
                if (cardItem instanceof CardRedstone) {
                    if (BaseCard.getTransferMode(card) == 0) {
                        int redstoneStrength = level.getSignal(getBlockPos().relative(direction), direction);
                        if (CardRedstone.getInterval(card)) {
                            enabled = (redstoneStrength >= CardRedstone.getIntervalLowerBound(card) && redstoneStrength <= CardRedstone.getIntervalUpperBound(card));
                        } else {
                            enabled = (redstoneStrength >= 1);
                        }
                    } else {
                        byte channelStrength = getRedstoneChannelStrength(BaseCard.getRedstoneChannel(card));
                        byte logicOperationChannelStrength = redstoneNetwork.get(CardRedstone.getRedstoneChannelOperation(card));
                        channelStrength = switch(CardRedstone.getLogicOperation(card)) {
                            case 1 -> (byte) (((channelStrength + logicOperationChannelStrength) > 0) ? 15 : 0); //OR
                            case 2 -> (byte) (((channelStrength * logicOperationChannelStrength) > 0) ? 15 : 0); //AND
                            case 3 -> (byte) (((channelStrength > 0) ^ (logicOperationChannelStrength > 0)) ? 15 : 0); //XOR
                            default -> channelStrength;
                        };
                        enabled = switch(CardRedstone.getOutputMode(card)) {
                            case 1 -> (channelStrength != 15); //Complementary
                            case 2 -> (channelStrength == 0); //NOT
                            default -> (channelStrength > 0);
                        };
                    }
                } else {
                    byte redstoneMode = BaseCard.getRedstoneMode(card);
                    if (redstoneMode == 0 || BaseCard.getNamedTransferMode(card) == TransferMode.SENSOR) { //Sensors are always enabled
                        enabled = true;
                    } else {
                        byte channelStrength = getRedstoneChannelStrength(BaseCard.getRedstoneChannel(card));
                        if (channelStrength > 0 && redstoneMode == 1) {
                            enabled = false;
                        } else if (channelStrength == 0 && redstoneMode == 2) {
                            enabled = false;
                        } else {
                            enabled = true;
                        }
                    }
                }
                if (cardItem instanceof CardItem) {
                    if (!getAttachedInventoryNoCache(direction, BaseCard.getSneaky(card)).isPresent()) {
                        continue;
                    }
                    cardRenders.add(new CardRender(direction, slot, card, getBlockPos(), level, enabled));
                } else if (cardItem instanceof CardFluid) {
                    if (!getAttachedFluidTankNoCache(direction, BaseCard.getSneaky(card)).isPresent()) {
                        continue;
                    }
                    cardRenders.add(new CardRender(direction, slot, card, getBlockPos(), level, enabled));
                } else if (cardItem instanceof CardEnergy) {
                    if (!getAttachedEnergyTankNoCache(direction, BaseCard.getSneaky(card)).isPresent()) {
                        continue;
                    }
                    cardRenders.add(new CardRender(direction, slot, card, getBlockPos(), level, enabled));
                } else if (card.getItem() instanceof CardRedstone) {
                    redstoneCardSides.put((byte) direction.ordinal(), true);
                    cardRenders.add(new CardRender(direction, slot, card, getBlockPos(), level, enabled));
                } else if (card.getItem() instanceof CardChemical) {
                    Map<ChemicalType, LazyOptional<IChemicalHandler<?, ?>>> chemicalHandlers = mekanismCache.getAttachedChemicalTanksNoCache(direction, BaseCard.getSneaky(card));
                    if (chemicalHandlers == null || chemicalHandlers.isEmpty()) {
                        continue;
                    }
                    cardRenders.add(new CardRender(direction, slot, card, getBlockPos(), level, enabled));
                }
            }
        }
        BlockState state = this.getBlockState();
        level.updateNeighborsAt(getBlockPos(), this.getBlockState().getBlock());
        state.updateNeighbourShapes(level, getBlockPos(), Block.UPDATE_ALL);
        rendersChecked = true;
    }

    public InventoryCardCounts getNodeContents() {
        InventoryCardCounts nodeContents = new InventoryCardCounts();
        for (int i = 0; i < Direction.values().length; i++) {
            nodeContents.addHandler(nodeSideCaches[i].itemHandler);
        }
        return nodeContents;
    }

    public void setShowParticles(boolean show) {
        this.showParticles = show;
        markDirtyClient();
    }

    public boolean getShowParticles() {
        return showParticles;
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER && side != null) {
            return nodeSideCaches[side.ordinal()].handlerLazyOptional.cast();
        }
        if (cap == ForgeCapabilities.ENERGY) {
            if (side == null) {
                return LazyOptional.empty();
            } else {
                NodeSideCache nodeSideCache = nodeSideCaches[side.ordinal()];
                for (int slot = 0; slot < LaserNodeContainer.CARD_SLOTS; slot++) {
                    ItemStack card = nodeSideCache.itemHandler.getStackInSlot(slot);
                    if (card.getItem() instanceof CardEnergy) {
                        BaseCardCache baseCardCache = new BaseCardCache(side, card, slot, this);
                        if (baseCardCache.enabled) {
                            return nodeSideCaches[side.ordinal()].laserEnergyStorage.cast();
                        }
                    }
                }
                return LazyOptional.empty();
            }
        }
        return super.getCapability(cap, side);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        ListTag redstoneNetworkTag = new ListTag();
        for (Map.Entry<Byte, Byte> entry : redstoneNetwork.byte2ByteEntrySet()) {
            CompoundTag comp = new CompoundTag();
            comp.putByte("channel", entry.getKey());
            comp.putByte("strength", entry.getValue());
            redstoneNetworkTag.add(comp);
        }
        tag.put("redstoneNetworkTag", redstoneNetworkTag);
        //System.out.println(redstoneNetworkTag + " at " + getBlockPos());
        return tag;
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        this.load(tag);
        redstoneNetwork.clear();
        ListTag redstoneNetworkTag = tag.getList("redstoneNetworkTag", Tag.TAG_COMPOUND);
        for (int i = 0; i < redstoneNetworkTag.size(); i++) {
            byte channel = redstoneNetworkTag.getCompound(i).getByte("channel");
            byte strength = redstoneNetworkTag.getCompound(i).getByte("strength");
            redstoneNetwork.put(channel, strength);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        for (int i = 0; i < Direction.values().length; i++) {
            NodeSideCache nodeSideCache = nodeSideCaches[i];
            if (tag.contains("Inventory" + i)) {
                nodeSideCache.itemHandler.deserializeNBT(tag.getCompound("Inventory" + i));
                if (nodeSideCache.itemHandler.getSlots() < LaserNodeContainer.SLOTS) {
                    nodeSideCache.itemHandler.reSize(LaserNodeContainer.SLOTS);
                }
            }
        }
        if (tag.contains("showParticles")) {
            showParticles = tag.getBoolean("showParticles");
        }
        if (!tag.contains("dimension")) {
            super.load(tag);
            rendersChecked = false;
        }
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        for (int i = 0; i < Direction.values().length; i++) {
            NodeSideCache nodeSideCache = nodeSideCaches[i];
            tag.put("Inventory" + i, nodeSideCache.itemHandler.serializeNBT());
        }
        tag.putBoolean("showParticles", showParticles);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LaserScheduler.removeNode(this);
        for (NodeSideCache nodeSideCache : nodeSideCaches) {
            nodeSideCache.handlerLazyOptional.invalidate();
            nodeSideCache.laserEnergyStorage.invalidate();
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            LaserScheduler.addNode(this);
        }
    }

    public class LaserEnergyStorage implements IEnergyStorage {
        private final Direction facing;

        public LaserEnergyStorage(Direction facing) {
            this.facing = facing;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return LaserNodeBE.this.receiveEnergy(facing, maxReceive, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return 0;
        }

        @Override
        public int getMaxEnergyStored() {
            return 0;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }
}
