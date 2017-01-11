/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.StructuralResources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.audio.events.PlaySoundEvent;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.event.EventPriority;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.logic.characters.events.AttackEvent;
import org.terasology.logic.common.ActivateEvent;
import org.terasology.logic.inventory.events.MoveItemEvent;
import org.terasology.logic.location.LocationComponent;
import org.terasology.logic.players.LocalPlayer;
import org.terasology.math.ChunkMath;
import org.terasology.math.Side;
import org.terasology.math.geom.Vector3i;
import org.terasology.registry.In;
import org.terasology.utilities.Assets;
import org.terasology.world.BlockEntityRegistry;
import org.terasology.world.WorldProvider;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockComponent;
import org.terasology.world.block.BlockManager;
import org.terasology.world.block.entity.placement.PlaceBlocks;
import org.terasology.world.block.family.BlockFamily;

@RegisterSystem
public class IngotStackSystem extends BaseComponentSystem {
    private static final Logger logger = LoggerFactory.getLogger(IngotStackSystem.class);

    private static final int INGOTS_PER_LAYER = 2;
    private static final int MAX_LAYERS = 3;
    private static final int MAX_INGOTS = MAX_LAYERS * INGOTS_PER_LAYER;
    private static final String LAYER_1_URI = "StructuralResources:IngotStack_01";
    private static final String LAYER_2_URI = "StructuralResources:IngotStack_02";
    private static final String LAYER_3_URI = "StructuralResources:IngotStack_03";

    @In
    private WorldProvider worldProvider;
    @In
    private BlockManager blockManager;
    @In
    private BlockEntityRegistry blockEntityRegistry;
    @In
    private LocalPlayer localPlayer;

    @ReceiveEvent(priority = EventPriority.PRIORITY_HIGH)
    public void onRightClick(ActivateEvent event, EntityRef entity, IngotComponent ingotComponent) {
        EntityRef instigator = event.getInstigator();
        BlockComponent targetBlockComponent = event.getTarget().getComponent(BlockComponent.class);
        if (targetBlockComponent == null) {
            event.consume();
            return;
        }

        Side surfaceSide = Side.inDirection(event.getHitNormal());
        Side secondaryDirection = ChunkMath.getSecondaryPlacementDirection(event.getDirection(), event.getHitNormal());
        Vector3i blockPos = new Vector3i(targetBlockComponent.getPosition());
        Vector3i targetPos = new Vector3i(blockPos).add(surfaceSide.getVector3i());
        IngotStackComponent stackComponent = event.getTarget().getComponent(IngotStackComponent.class);

        if (stackComponent != null && stackComponent.ingots < MAX_INGOTS) {
            EntityRef stackEntity = event.getTarget();
            instigator.send(new PlaySoundEvent(Assets.getSound("engine:PlaceBlock").get(), 0.5f));

            MoveItemEvent moveEvent = new MoveItemEvent(instigator, stackEntity);
            moveEvent.setMoveSelectedItem(1);
            instigator.send(moveEvent);
            updateStackSize(-1, stackEntity, stackEntity.getComponent(IngotStackComponent.class));

        } else if (canPlaceBlock(blockPos, targetPos)) {
            Block newStackBlock = blockManager.getBlockFamily(LAYER_1_URI)
                    .getBlockForPlacement(worldProvider, blockEntityRegistry, targetPos, surfaceSide, secondaryDirection);
            PlaceBlocks placeNewIngotStack = new PlaceBlocks(targetPos, newStackBlock, instigator);
            worldProvider.getWorldEntity().send(placeNewIngotStack);
            instigator.send(new PlaySoundEvent(Assets.getSound("engine:PlaceBlock").get(), 0.5f));

            EntityRef stackEntity = blockEntityRegistry.getBlockEntityAt(targetPos);
            MoveItemEvent moveEvent = new MoveItemEvent(instigator, stackEntity);
            moveEvent.setMoveByComponent(1, IngotComponent.class);
            instigator.send(moveEvent);
            updateStackSize(-1, stackEntity, stackEntity.getComponent(IngotStackComponent.class));
        }

        event.consume();
    }

    @ReceiveEvent(priority = EventPriority.PRIORITY_HIGH)
    public void onLeftClick(AttackEvent event, EntityRef stackEntity, IngotStackComponent stackComponent) {
        EntityRef instigator = event.getInstigator();
        if (stackComponent.ingots > 0) {
            MoveItemEvent moveEvent = new MoveItemEvent(stackEntity, instigator);
            moveEvent.setMoveFirstOption(-1);
            instigator.send(moveEvent);
            instigator.send(new PlaySoundEvent(Assets.getSound("engine:Loot").get(), 0.5f));
            updateStackSize(-1, stackEntity, stackEntity.getComponent(IngotStackComponent.class));
        }
        event.consume();
    }

    // for real-time updates to the stack
    private void updateStackSize(int amount, EntityRef stackEntity, IngotStackComponent stackComponent) {
        EntityRef instigator = localPlayer.getCharacterEntity();
        LocationComponent locationComponent = stackEntity.getComponent(LocationComponent.class);
        Vector3i pos = new Vector3i(locationComponent.getWorldPosition());
        if (stackComponent.ingots + amount > MAX_INGOTS) {
            MoveItemEvent moveEvent = new MoveItemEvent(stackEntity, instigator);
            moveEvent.setMoveFirstOption(stackComponent.ingots + amount - MAX_INGOTS);
            instigator.send(moveEvent);
        }
        updateIngotStack(pos, Math.min(stackComponent.ingots + amount, MAX_INGOTS), instigator);
    }

    private void updateIngotStack(Vector3i stackPos, int ingots, EntityRef instigator) {
        EntityRef stackEntity = blockEntityRegistry.getBlockEntityAt(stackPos);
        Block stackBlock = worldProvider.getBlock(stackPos);
        String blockUriString = stackBlock.getBlockFamily().getURI().toString();

        if (ingots < 0 || ingots > MAX_INGOTS) {
            return;
        }
        if (ingots == 0) {
            worldProvider.setBlock(stackPos, blockManager.getBlock(BlockManager.AIR_ID));
            return;
        }
        if (!blockUriString.equalsIgnoreCase(LAYER_1_URI) && !blockUriString.equalsIgnoreCase(LAYER_2_URI) && !blockUriString.equalsIgnoreCase(LAYER_3_URI)) {
            // not an ingot block
            return;
        }

        IngotStackComponent stackComponent = stackEntity.getComponent(IngotStackComponent.class);
        int currentLayers = (stackComponent.ingots - 1) / INGOTS_PER_LAYER + 1;
        int newLayers = (ingots - 1) / INGOTS_PER_LAYER + 1;

        if (currentLayers != newLayers) {
            BlockFamily blockFamily;
            if (newLayers == 2) {
                blockFamily = blockManager.getBlockFamily(LAYER_2_URI);
            } else if (newLayers == 3) {
                blockFamily = blockManager.getBlockFamily(LAYER_3_URI);
            } else {
                blockFamily = blockManager.getBlockFamily(LAYER_1_URI);
            }
            Block newStackBlock = blockFamily.getBlockForPlacement(worldProvider, blockEntityRegistry, stackPos, Side.TOP, stackBlock.getDirection());
            PlaceBlocks placeNewIngotStack = new PlaceBlocks(stackPos, newStackBlock, instigator);
            worldProvider.getWorldEntity().send(placeNewIngotStack);
            stackEntity = blockEntityRegistry.getBlockEntityAt(stackPos);
        }
        stackComponent.ingots = ingots;
        stackEntity.saveComponent(stackComponent);
    }

    private boolean canPlaceBlock(Vector3i blockPos, Vector3i targetPos) {
        Block block = worldProvider.getBlock(blockPos);
        Block targetBlock = worldProvider.getBlock(targetPos);

        if (!block.isAttachmentAllowed()) {
            return false;
        }
        if (!targetBlock.isReplacementAllowed() || targetBlock.isTargetable()) {
            return false;
        }
        return true;
    }
}
