package dev.emi.emi.api.stack;

import java.util.List;

import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class EmptyEmiStack extends EmiStack {
	private static final Identifier ID = new Identifier("emi", "empty");
	public static final EmptyEntry ENTRY = new EmptyEntry();

	@Override
	public EmiStack getRemainder() {
		return EMPTY;
	}

	@Override
	public List<EmiStack> getEmiStacks() {
		return List.of();
	}

	@Override
	public EmiStack setRemainder(EmiStack stack) {
		throw new UnsupportedOperationException("Cannot mutate an empty stack");
	}

	@Override
	public EmiStack copy() {
		return EMPTY;
	}

	@Override
	public boolean isEmpty() {
		return true;
	}

	@Override
	public NbtCompound getNbt() {
		return null;
	}

	@Override
	public Object getKey() {
		return ENTRY;
	}

	@Override
	public Entry<?> getEntry() {
		return ENTRY;
	}

	@Override
	public Identifier getId() {
		return ID;
	}

	@Override
	public boolean isEqual(EmiStack stack) {
		return stack == EMPTY;
	}

	@Override
	public void renderIcon(MatrixStack matrices, int x, int y, float delta) {
	}

	@Override
	public void renderOverlay(MatrixStack matrices, int x, int y, float delta) {
	}

	@Override
	public List<Text> getTooltipText() {
		return List.of();
	}

	@Override
	public List<TooltipComponent> getTooltip() {
		return List.of();
	}

	@Override
	public Text getName() {
		return new LiteralText("");
	}

	public static class EmptyEntry extends Entry<ItemStack> {

		public EmptyEntry() {
			super(ItemStack.EMPTY);
		}

		@Override
		Class<ItemStack> getType() {
			return ItemStack.class;
		}
	}
}