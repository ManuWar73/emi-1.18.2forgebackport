package dev.emi.emi.jemi;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.text.Text;

public class JemiCategory extends EmiRecipeCategory {
	public IRecipeCategory<?> category;

	public JemiCategory(IRecipeCategory<?> category) {
		super(category.getRecipeType().getUid(), (matrices, x, y, delta) -> {
			IDrawable icon = category.getIcon();
			if (icon != null) {
				icon.draw(matrices, x + (16 - icon.getWidth()) / 2, y + (16 - icon.getHeight()) / 2);
			} else {
				MinecraftClient client = MinecraftClient.getInstance();
				String title = category.getTitle().getString();
				DrawableHelper.drawCenteredTextWithShadow(matrices, client.textRenderer, EmiPort.literal(title.substring(0, Math.min(2, title.length()))).asOrderedText(), x + 8, y + 2, -1);
			}
		});
		this.category = category;
	}

	@Override
	public Text getName() {
		return category.getTitle();
	}
}
