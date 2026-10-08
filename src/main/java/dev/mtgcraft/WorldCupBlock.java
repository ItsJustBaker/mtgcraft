package dev.mtgcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The MTG World Cup as a trophy you can place. On top of a Magic Table it unlocks reward duels nearby. */
public class WorldCupBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 15, 13);

    public WorldCupBlock(Properties props) {
        super(props);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }
}
