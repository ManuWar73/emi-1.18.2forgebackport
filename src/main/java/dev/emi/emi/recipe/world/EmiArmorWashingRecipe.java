package dev.emi.emi.recipe.world;

import java.util.List;

import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.GeneratedSlotWidget;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.TextureWidget;
import dev.emi.emi.api.widget.Widget;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.DyeableItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;

public class EmiArmorWashingRecipe extends EmiCustomWorldRecipe {
	private static final EmiStack CAULDRON = EmiStack.of(Items.CAULDRON);
	private static final EmiStack WATER = EmiStack.of(FluidVariant.of(Fluids.WATER), 81_000 / 3);
	private final EmiStack emiArmor;
	private final Item armor;
	private final int unique = EmiUtil.RANDOM.nextInt();

	static {
		CAULDRON.setRemainder(CAULDRON);
	}

	public EmiArmorWashingRecipe(Item armor, Identifier id) {
		super(id);
		this.armor = armor;
		emiArmor = EmiStack.of(armor);
	}

	@Override
	public List<EmiIngredient> getInputs() {
		return List.of(emiArmor, WATER);
	}

	@Override
	public List<EmiIngredient> getCatalysts() {
		return List.of(CAULDRON);
	}

	@Override
	public List<EmiStack> getOutputs() {
		return List.of(emiArmor);
	}

	@Override
	public void addWidgets(List<Widget> widgets, int x, int y) {
		widgets.add(new TextureWidget(EmiRenderHelper.WIDGETS, x + 23, y + 3, 13, 13, 82, 0));
		widgets.add(new TextureWidget(EmiRenderHelper.WIDGETS, x + 79, y + 1, 24, 17, 44, 0));
		widgets.add(new GeneratedSlotWidget(r -> {
			ItemStack stack = new ItemStack(armor);
			((DyeableItem) armor).setColor(stack, r.nextInt(0xFFFFFF + 1));
			return EmiStack.of(stack);
		}, unique, x, y));
		widgets.add(new SlotWidget(CAULDRON, x + 40, y));
		widgets.add(new SlotWidget(WATER, x + 58, y));
		widgets.add(new SlotWidget(emiArmor, x + 107, y).recipeContext(this));
	}

	@Override
	public boolean supportsRecipeTree() {
		return false;
	}
}
