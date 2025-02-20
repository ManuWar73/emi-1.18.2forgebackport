package dev.emi.emi.screen;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.compress.utils.Lists;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiFillAction;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.chess.EmiChess;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.config.HeaderType;
import dev.emi.emi.config.HelpLevel;
import dev.emi.emi.config.Margins;
import dev.emi.emi.config.ScreenAlign;
import dev.emi.emi.config.ScreenAlign.Horizontal;
import dev.emi.emi.config.SidebarPages;
import dev.emi.emi.config.SidebarSettings;
import dev.emi.emi.config.SidebarSide;
import dev.emi.emi.config.SidebarSubpanels;
import dev.emi.emi.config.SidebarTheme;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.input.EmiBind;
import dev.emi.emi.input.EmiInput;
import dev.emi.emi.network.CreateItemC2SPacket;
import dev.emi.emi.network.EmiNetwork;
import dev.emi.emi.platform.EmiClient;
import dev.emi.emi.registry.EmiDragDropHandlers;
import dev.emi.emi.registry.EmiExclusionAreas;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.registry.EmiStackProviders;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.runtime.EmiHidden;
import dev.emi.emi.runtime.EmiHistory;
import dev.emi.emi.runtime.EmiReloadLog;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.runtime.EmiSidebars;
import dev.emi.emi.screen.tooltip.RecipeTooltipComponent;
import dev.emi.emi.screen.widget.EmiSearchWidget;
import dev.emi.emi.screen.widget.SidebarButtonWidget;
import dev.emi.emi.screen.widget.SizedButtonWidget;
import dev.emi.emi.search.EmiSearch;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

public class EmiScreenManager {
	private static final int PADDING_SIZE = 1;
	private static final int ENTRY_SIZE = 16 + PADDING_SIZE * 2;
	private static final int SUBPANEL_SEPARATOR_SIZE = 3;
	private static MinecraftClient client = MinecraftClient.getInstance();
	private static List<? extends EmiIngredient> searchedStacks = List.of();
	private static int lastWidth, lastHeight;
	private static List<Bounds> lastExclusion;
	private static StackBatcher.ClaimedCollection batchers = new StackBatcher.ClaimedCollection();
	private static List<SidebarPanel> panels = List.of(
			new SidebarPanel(SidebarSide.LEFT, EmiConfig.leftSidebarPages),
			new SidebarPanel(SidebarSide.RIGHT, EmiConfig.rightSidebarPages),
			new SidebarPanel(SidebarSide.TOP, EmiConfig.topSidebarPages),
			new SidebarPanel(SidebarSide.BOTTOM, EmiConfig.bottomSidebarPages));
	// The last stack that was used to draw a tooltip, cleared each frame
	public static ItemStack lastStackTooltipRendered;
	public static EmiPlayerInventory lastPlayerInventory;
	public static int lastMouseX, lastMouseY;
	// The stack that was clicked on, for determining when a drag properly starts
	public static EmiIngredient pressedStack = EmiStack.EMPTY;
	public static EmiIngredient draggedStack = EmiStack.EMPTY;
	// Prevent users from clicking on the wrong thing as their index changes under
	// them
	private static EmiStackInteraction lastHoveredCraftable = null;
	// Whether the craftable has been used multiple times, indicating it shouldn't
	// disappear
	// Even if the recipe it was focusing becomes invalid
	private static boolean lastHoveredCraftableSturdy = false;
	private static int lastHoveredCraftableOffset = -1;
	private static double scrollAcc = 0;

	public static EmiSearchWidget search = new EmiSearchWidget(client.textRenderer, 0, 0, 160, 18);
	public static SizedButtonWidget emi = new SizedButtonWidget(0, 0, 20, 20, 204, 0,
			() -> true, (w) -> client.setScreen(new ConfigScreen(client.currentScreen)),
			List.of(EmiPort.translatable("tooltip.emi.config", EmiRenderHelper.getEmiText())));
	public static SizedButtonWidget tree = new SizedButtonWidget(0, 0, 20, 20, 184, 0,
			() -> true, (w) -> EmiApi.viewRecipeTree(),
			List.of(EmiPort.translatable("tooltip.emi.recipe_tree")));

	public static boolean isDisabled() {
		return !EmiReloadManager.isLoaded() || !EmiConfig.enabled;
	}

	public static void recalculate() {
		EmiPlayerInventory inv = EmiPlayerInventory.of(client.player);
		SidebarPanel searchPanel = getSearchPanel();
		if (searchPanel.space != null) {
			if (!inv.isEqual(lastPlayerInventory)) {
				lastPlayerInventory = inv;
				EmiSidebars.craftables = lastPlayerInventory.getCraftables();
				searchPanel.space.batcher.repopulate();
				if (searchPanel.getType() == SidebarType.CRAFTABLES) {
					EmiSearch.update();
				}
				EmiFavorites.updateSynthetic(inv);
				repopulatePanels(SidebarType.CRAFTABLES);
			}
			if (searchedStacks != EmiSearch.stacks) {
				searchPanel.space.batcher.repopulate();
				searchedStacks = EmiSearch.stacks;
			}
		}

		Screen screen = client.currentScreen;
		if (screen == null) {
			return;
		}
		List<Bounds> exclusion = EmiExclusionAreas.getExclusion(screen);
		if (lastWidth == screen.width && lastHeight == screen.height && exclusion.size() == lastExclusion.size()) {
			boolean same = true;
			for (int i = 0; i < exclusion.size(); i++) {
				Bounds a = exclusion.get(i);
				Bounds b = lastExclusion.get(i);
				if (a.x() != b.x() || a.y() != b.y() || a.width() != b.width() || a.height() != b.height()) {
					same = false;
					break;
				}
			}
			if (same) {
				return;
			}
		}
		for (SidebarPanel panel : panels) {
			for (ScreenSpace space : panel.getSpaces()) {
				space.batcher.repopulate();
			}
		}
		lastWidth = screen.width;
		lastHeight = screen.height;
		lastExclusion = exclusion;
		if (screen instanceof EmiScreen emi) {
			int left = Math.max(ENTRY_SIZE * 2, emi.emi$getLeft());
			int right = Math.min(screen.width - ENTRY_SIZE * 2, emi.emi$getRight());
			int top = emi.emi$getTop();
			int bottom = emi.emi$getBottom();

			batchers.unclaimAll();

			List<Bounds> spaceExclusion = Lists.newArrayList();
			spaceExclusion.addAll(exclusion);

			createScreenSpace(panels.get(0), screen, spaceExclusion, false,
					new Bounds(0, 0, left, screen.height),
					SidebarSettings.LEFT);

			createScreenSpace(panels.get(1), screen, spaceExclusion, true,
					new Bounds(right, 0, screen.width - right, screen.height),
					SidebarSettings.RIGHT);

			spaceExclusion = Lists.newArrayList();
			if (panels.get(0).isVisible()) {
				spaceExclusion.add(panels.get(0).getBounds());
			}
			if (panels.get(1).isVisible()) {
				spaceExclusion.add(panels.get(1).getBounds());
			}
			spaceExclusion.addAll(exclusion);

			int topCenter = EmiConfig.topSidebarSize.values.getInt(0) * ENTRY_SIZE / 2 + EmiConfig.topSidebarTheme.horizontalPadding;
			int topSpaceBottom = switch (EmiConfig.topSidebarAlign.horizontal) {
				case LEFT -> getVerticalConstraint(panels.get(0), EmiConfig.topSidebarMargins.left() + topCenter, top, screen.height, true);
				case CENTER -> top;
				case RIGHT -> getVerticalConstraint(panels.get(1), right - EmiConfig.topSidebarMargins.right() + topCenter, top, screen.height, true);
			};
			boolean topRtl = EmiConfig.topSidebarAlign.horizontal == Horizontal.RIGHT;

			createScreenSpace(panels.get(2), screen, spaceExclusion, topRtl,
					new Bounds(0, 0, screen.width, topSpaceBottom),
					SidebarSettings.TOP);

			int bottomCenter = EmiConfig.bottomSidebarSize.values.getInt(0) * ENTRY_SIZE / 2 + EmiConfig.bottomSidebarTheme.horizontalPadding;
			int bottomSpaceTop = switch (EmiConfig.bottomSidebarAlign.horizontal) {
				case LEFT -> getVerticalConstraint(panels.get(0), EmiConfig.bottomSidebarMargins.left() + bottomCenter, bottom, 0, false);
				case CENTER -> bottom;
				case RIGHT -> getVerticalConstraint(panels.get(1), EmiConfig.bottomSidebarMargins.right() + bottomCenter, bottom, 0, false);
			};
			boolean bottomRtl = EmiConfig.bottomSidebarAlign.horizontal == Horizontal.RIGHT;

			createScreenSpace(panels.get(3), screen, spaceExclusion, bottomRtl,
					new Bounds(0, bottomSpaceTop, screen.width, screen.height - bottomSpaceTop),
					SidebarSettings.BOTTOM);

			updateSidebarButtons();
		}
	}

	public static void forceRecalculate() {
		lastWidth = -1;
		lastPlayerInventory = null;
		recalculate();
	}

	private static int getVerticalConstraint(SidebarPanel panel, int cx, int def, int max, boolean top) {
		if (panel.isVisible()) {
			Bounds bounds = panel.getBounds();
			if (bounds.x() <= cx && bounds.right() >= cx) {
				return top ? Math.max(def, bounds.top()) : Math.min(def, bounds.bottom());
			}
		}
		return max;
	}

	private static void createScreenSpace(SidebarPanel panel, Screen screen, List<Bounds> exclusion,
			boolean rtl, Bounds bounds, SidebarSettings settings) {
		Margins margins = settings.margins();
		ScreenAlign align = settings.align();
		SidebarTheme theme = settings.theme();
		SidebarSubpanels subpanels = settings.subpanels();
		boolean header = settings.header() == HeaderType.VISIBLE;

		int maxWidth = settings.size().values.getInt(0);
		int maxHeight = settings.size().values.getInt(1);

		int subpanelHeight = 0;
		for (SidebarSubpanels.Subpanel subpanel : subpanels.subpanels) {
			subpanelHeight += subpanel.rows() * ENTRY_SIZE + SUBPANEL_SEPARATOR_SIZE;
			maxHeight -= subpanel.rows();
		}

		if (panel.getType() == SidebarType.CHESS) {
			maxWidth = 8;
			maxHeight = 8;
			theme = SidebarTheme.MODERN;
		}
		int cx = bounds.x() + bounds.width() / 2;
		int cy = bounds.y() + bounds.height() / 2;
		int headerOffset = header ? 18 : 0;

		// Try a more optimistic approach to position the bounding box slightly more
		// pleasantly if applicable
		int idealWidth = Math.min(
				maxWidth * ENTRY_SIZE + margins.left() + margins.right() + theme.horizontalPadding * 2, bounds.width());
		int idealHeight = Math.min(
				maxHeight * ENTRY_SIZE + margins.top() + margins.bottom() + theme.verticalPadding * 2 + headerOffset + subpanelHeight,
				bounds.height());
		int idealX = switch (align.horizontal) {
			case LEFT -> bounds.x();
			case CENTER -> bounds.x() + bounds.width() / 2 - idealWidth / 2;
			case RIGHT -> bounds.right() - idealWidth;
		};
		int idealY = switch (align.horizontal) {
			case LEFT -> bounds.y();
			case CENTER -> bounds.y() + bounds.height() / 2 - idealHeight / 2;
			case RIGHT -> bounds.bottom() - idealHeight;
		};
		Bounds idealBounds = constrainBounds(exclusion, new Bounds(idealX, idealY, idealWidth, idealHeight), align,
				headerOffset);

		bounds = constrainBounds(exclusion, bounds, align, headerOffset);

		if (Math.min(idealWidth, idealBounds.width()) * Math.min(idealHeight, idealBounds.height()) > Math
				.min(idealWidth, bounds.width()) * Math.min(idealHeight, bounds.height())) {
			bounds = idealBounds;
		}

		int xMin = bounds.left() + margins.left() + theme.horizontalPadding;
		int xMax = bounds.right() - margins.right() - theme.horizontalPadding;
		int yMin = bounds.top() + margins.top() + theme.verticalPadding;
		int yMax = bounds.bottom() - margins.bottom() - theme.verticalPadding;
		int xSpan = xMax - xMin;
		int ySpan = yMax - yMin;
		int tw = Math.max(0, Math.min((xSpan) / ENTRY_SIZE, maxWidth));
		int th = Math.max(0, Math.min((ySpan - headerOffset - subpanelHeight) / ENTRY_SIZE, maxHeight));
		int hl = xMin;
		int hr = xMax - tw * ENTRY_SIZE;
		int tx = switch (align.horizontal) {
			case LEFT -> hl;
			case CENTER -> MathHelper.clamp(cx - (tw * ENTRY_SIZE) / 2, hl, hr);
			case RIGHT -> hr;
		};
		int vt = yMin + headerOffset;
		int vb = yMax - th * ENTRY_SIZE - subpanelHeight;
		int ty = switch (align.vertical) {
			case TOP -> vt;
			case CENTER -> MathHelper.clamp(cy - (th * ENTRY_SIZE - headerOffset + subpanelHeight + theme.verticalPadding / 2) / 2, vt, vb);
			case BOTTOM -> vb;
		};
		panel.header = header;
		panel.theme = theme;
		ScreenSpace space = new ScreenSpace(tx, ty, tw, th, rtl, exclusion, () -> panel.getType(), panel.isSearch());
		List<ScreenSpace> subspaces = Lists.newArrayList();
		for (SidebarSubpanels.Subpanel subpanel : subpanels.subpanels) {
			ty += th * ENTRY_SIZE + SUBPANEL_SEPARATOR_SIZE;
			th = subpanel.rows();
			subspaces.add(new ScreenSpace(tx, ty, tw, th, rtl, exclusion, () -> subpanel.type, false));
		}
		panel.setSpaces(space, subspaces);
	}

	private static Bounds constrainBounds(List<Bounds> exclusion, Bounds bounds, ScreenAlign align, int headerOffset) {
		for (int i = 0; i < exclusion.size(); i++) {
			Bounds overlap = exclusion.get(i).overlap(bounds);
			if (!overlap.empty()) {
				if (overlap.top() < bounds.top() + ENTRY_SIZE + headerOffset || overlap.width() >= bounds.width() / 2
						|| overlap.height() >= bounds.height() / 3) {
					int widthFactor = overlap.width() * 10 / bounds.width();
					int heightFactor = overlap.height() * 10 / bounds.height();
					if (heightFactor < widthFactor) {
						int cy = bounds.y() + bounds.height() / 2;
						int ocy = overlap.y() + overlap.height() / 2;
						cy += switch (align.vertical) {
							case TOP -> -bounds.height() / 4;
							case CENTER -> 0;
							case BOTTOM -> bounds.height() / 4;
						};
						if (cy < ocy) {
							bounds = new Bounds(bounds.x(), bounds.y(), bounds.width(), overlap.top() - bounds.top());
						} else {
							bounds = new Bounds(bounds.x(), overlap.bottom(), bounds.width(),
									bounds.bottom() - overlap.bottom());
						}
					} else {
						int cx = bounds.x() + bounds.width() / 2;
						int ocx = overlap.x() + overlap.width() / 2;
						cx += switch (align.horizontal) {
							case LEFT -> -bounds.width() / 4;
							case CENTER -> 0;
							case RIGHT -> bounds.width() / 4;
						};
						if (cx < ocx) {
							bounds = new Bounds(bounds.x(), bounds.y(), overlap.left() - bounds.left(),
									bounds.height());
						} else {
							bounds = new Bounds(overlap.right(), bounds.y(), bounds.right() - overlap.right(),
									bounds.height());
						}
					}
					i = -1;
				}
			}
		}
		if (bounds.empty()) {
			return Bounds.EMPTY;
		}
		return bounds;
	}

	public static void focusSearchSidebarType(SidebarType type) {
		if (getSearchPanel().supportsType(type)) {
			getSearchPanel().setType(type);
		}
	}

	public static void focusSidebarType(SidebarType type) {
		for (SidebarPanel panel : panels) {
			if (panel.supportsType(type)) {
				panel.setType(type);
			}
		}
	}

	public static @Nullable SidebarPanel getPanelFor(SidebarSide side) {
		for (SidebarPanel panel : panels) {
			if (panel.side == side) {
				return panel;
			}
		}
		return null;
	}

	public static @Nullable SidebarPanel getPanelFor(SidebarType type) {
		for (SidebarPanel panel : panels) {
			if (panel.getType() == type) {
				return panel;
			}
		}
		return null;
	}

	public static @Nullable SidebarPanel getHoveredPanel(int mouseX, int mouseY) {
		for (SidebarPanel panel : panels) {
			if (panel.getBounds().contains(mouseX, mouseY) && panel.isVisible()) {
				return panel;
			}
		}
		return null;
	}

	public static @Nullable ScreenSpace getHoveredSpace(int mouseX, int mouseY) {
		SidebarPanel panel = getHoveredPanel(mouseX, mouseY);
		if (panel != null) {
			return panel.getHoveredSpace(mouseX, mouseY);
		}
		return null;
	}

	public static boolean hasSidebarAvailable(SidebarType type) {
		for (SidebarPanel panel : panels) {
			if (panel.supportsType(type)) {
				return true;
			}
		}
		return false;
	}

	public static void repopulatePanels(SidebarType type) {
		for (SidebarPanel panel : panels) {
			for (ScreenSpace space : panel.getSpaces()) {
				if (space.getType() == type) {
					space.batcher.repopulate();
				}
			}
		}
	}

	public static SidebarPanel getSearchPanel() {
		for (SidebarPanel panel : panels) {
			if (panel.isSearch()) {
				return panel;
			}
		}
		return panels.get(1);
	}

	public static void toggleSidebarType(SidebarType type) {
		boolean visible = false;
		for (SidebarPanel panel : panels) {
			if (panel.getType() == type) {
				visible = true;
				panel.cycleType(1);
			}
		}
		if (!visible) {
			focusSidebarType(type);
		}
	}

	public static List<? extends EmiIngredient> getSearchSource() {
		return EmiSidebars.getStacks(getSearchPanel().getType());
	}

	public static EmiStackInteraction getHoveredStack(int mouseX, int mouseY, boolean notClick) {
		return getHoveredStack(mouseX, mouseY, notClick, false);
	}

	public static EmiStackInteraction getHoveredStack(int mouseX, int mouseY, boolean notClick,
			boolean ignoreLastHoveredCraftable) {
		if (client.currentScreen == null) {
			return EmiStackInteraction.EMPTY;
		}
		EmiStackInteraction stack = EmiStackProviders.getStackAt(client.currentScreen, mouseX, mouseY, notClick);
		if (!stack.isEmpty()) {
			return stack;
		}
		if (!ignoreLastHoveredCraftable) {
			if (lastHoveredCraftable != null) {
				if (lastHoveredCraftable.getRecipeContext() == null
						|| (!lastHoveredCraftableSturdy && lastPlayerInventory != null &&
							!lastPlayerInventory.canCraft(lastHoveredCraftable.getRecipeContext()))) {
					lastHoveredCraftable = null;
				} else {
					return lastHoveredCraftable;
				}
			}
		}
		for (SidebarPanel panel : panels) {
			for (ScreenSpace space : panel.getSpaces()) {
				if (panel.isVisible() && space.pageSize > 0 && space.contains(mouseX, mouseY)
						&& mouseX >= space.tx && mouseY >= space.ty) {
					int x = (mouseX - space.tx) / ENTRY_SIZE;
					int y = (mouseY - space.ty) / ENTRY_SIZE;
					int n = space.getRawOffset(x, y);
					if (n >= 0 && space == panel.space) {
						n += space.pageSize * panel.page;
					}
					if (n >= 0 && n < space.getStacks().size()) {
						EmiIngredient hovered = space.getStacks().get(n);
						if (hovered instanceof EmiFavorite fav) {
							return new SidebarEmiStackInteraction(hovered, space, fav.getRecipe(), true);
						}
						return new SidebarEmiStackInteraction(hovered, space);
					}
				}
			}
		}
		if (lastStackTooltipRendered != null && notClick) {
			return new EmiStackInteraction(EmiStack.of(lastStackTooltipRendered));
		}
		return EmiStackInteraction.EMPTY;
	}

	private static void updateMouse(int mouseX, int mouseY) {
		if (lastHoveredCraftable != null) {
			ScreenSpace space = getHoveredSpace(mouseX, mouseY);
			if (space != null && (space.getType() == SidebarType.CRAFTABLES || space.getType() == SidebarType.CRAFT_HISTORY)) {
				int offset = space.getRawOffsetFromMouse(mouseX, mouseY);
				if (offset != lastHoveredCraftableOffset) {
					lastHoveredCraftable = null;
				}
			} else {
				lastHoveredCraftable = null;
			}
		}
		lastMouseX = mouseX;
		lastMouseY = mouseY;
	}

	public static void drawBackground(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		updateMouse(mouseX, mouseY);
		recalculate();
		Screen screen = client.currentScreen;
		if (screen == null) {
			return;
		}
		if (screen instanceof EmiScreen emi) {
			client.getProfiler().push("sidebar");
			for (SidebarPanel panel : panels) {
				panel.drawBackground(matrices, mouseX, mouseY, delta);
			}
		}
	}

	public static void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		client.getProfiler().push("emi");
		updateMouse(mouseX, mouseY);
		recalculate();
		Screen screen = client.currentScreen;
		if (screen == null) {
			return;
		}
		boolean visible = !isDisabled();
		emi.visible = visible;
		tree.visible = visible;
		for (SidebarPanel panel : panels) {
			panel.updateWidgetVisibility();
		}
		if (isDisabled()) {
			if (!EmiReloadManager.isLoaded()) {
				if (EmiReloadManager.getStatus() == -1) {
					client.textRenderer.drawWithShadow(matrices, EmiPort.translatable("emi.reloading.error"), 4, screen.height - 16, -1);
				} else if (EmiReloadManager.getStatus() == 0) {
					client.textRenderer.drawWithShadow(matrices, EmiPort.translatable("emi.reloading.waiting"), 4, screen.height - 16, -1);
				} else {
					client.textRenderer.drawWithShadow(matrices, EmiPort.translatable("emi.reloading"), 4, screen.height - 16, -1);
					client.textRenderer.drawWithShadow(matrices, EmiReloadManager.reloadStep, 4, screen.height - 26, -1);
					if (System.currentTimeMillis() > EmiReloadManager.reloadWorry) {
						client.textRenderer.drawWithShadow(matrices, EmiPort.translatable("emi.reloading.worry"), 4, screen.height - 36, -1);
					}
				}
			}
			client.getProfiler().pop();
			lastHoveredCraftable = null;
			return;
		}
		renderWidgets(matrices, mouseX, mouseY, delta, screen);
		client.getProfiler().push("sidebars");
		if (screen instanceof EmiScreen emi) {
			client.getProfiler().push("sidebar");
			for (SidebarPanel panel : panels) {
				panel.render(matrices, mouseX, mouseY, delta);
			}

			renderLastHoveredCraftable(matrices, mouseX, mouseY, delta, screen);

			client.getProfiler().pop();
		}

		renderDevMode(matrices, mouseX, mouseY, delta, screen);
		client.getProfiler().pop();

		renderExclusionAreas(matrices, mouseX, mouseY, delta, screen);
		client.getProfiler().pop();

		RenderSystem.disableDepthTest();
	}

	public static void drawForeground(MatrixStack matrices, int mouseX, int mouseY, float delta) {
		Screen screen = client.currentScreen;
		if (screen instanceof EmiScreen && !isDisabled()) {
			renderDraggedStack(matrices, mouseX, mouseY, delta, screen);
			renderCurrentTooltip(matrices, mouseX, mouseY, delta, screen);
		}
	}

	private static void renderWidgets(MatrixStack matrices, int mouseX, int mouseY, float delta, Screen screen) {
		matrices.push();
		matrices.translate(0, 0, 100);
		emi.render(matrices, mouseX, mouseY, delta);
		tree.render(matrices, mouseX, mouseY, delta);
		search.render(matrices, mouseX, mouseY, delta);
		matrices.pop();
	}

	private static void renderLastHoveredCraftable(MatrixStack matrices, int mouseX, int mouseY, float delta,
			Screen screen) {
		if (lastHoveredCraftable != null && lastHoveredCraftableOffset != -1) {
			EmiStackInteraction cur = getHoveredStack(mouseX, mouseY, false, true);
			if (cur.getRecipeContext() != lastHoveredCraftable.getRecipeContext()) {
				ScreenSpace space = getHoveredSpace(mouseX, mouseY);
				if (space != null && (space.getType() == SidebarType.CRAFTABLES || space.getType() == SidebarType.CRAFT_HISTORY)) {
					MatrixStack view = RenderSystem.getModelViewStack();
					view.push();
					view.translate(0, 0, 200);
					RenderSystem.applyModelViewMatrix();
					int lhx = space.getRawX(lastHoveredCraftableOffset);
					int lhy = space.getRawY(lastHoveredCraftableOffset);
					DrawableHelper.fill(matrices, lhx, lhy, lhx + 18, lhy + 18, 0x44AA00FF);
					lastHoveredCraftable.getStack().render(matrices, lhx + 1, lhy + 1, delta,
							EmiIngredient.RENDER_ICON);
					view.pop();
					RenderSystem.applyModelViewMatrix();
				}
			}
		}
	}

	private static void renderDraggedStack(MatrixStack matrices, int mouseX, int mouseY, float delta, Screen screen) {
		if (!draggedStack.isEmpty()) {
			SidebarPanel panel = getHoveredPanel(mouseX, mouseY);
			if (panel != null) {
				ScreenSpace space = panel.getHoveredSpace(mouseX, mouseY);
				if (space != null && space.getType() == SidebarType.FAVORITES) {
					int pageSize = space.pageSize;
					int page = panel.page;
					int index = space.getClosestEdge(mouseX, mouseY);
					if (index + pageSize * page > EmiFavorites.favorites.size()) {
						index = EmiFavorites.favorites.size() - pageSize * page;
					}
					if (index + pageSize * page > space.getStacks().size()) {
						index = space.getStacks().size() - pageSize * page;
					}
					if (index >= 0) {
						matrices.push();
						matrices.translate(0, 0, 200);
						int dx = space.getEdgeX(index);
						int dy = space.getEdgeY(index);
						DrawableHelper.fill(matrices, dx - 1, dy, dx + 1, dy + 18, 0xFF00FFFF);
						matrices.pop();
					}
				}
			}
			EmiDragDropHandlers.render(screen, draggedStack, matrices, mouseX, mouseY, delta);
			MatrixStack view = RenderSystem.getModelViewStack();
			view.push();
			view.translate(0, 0, 400);
			RenderSystem.applyModelViewMatrix();
			draggedStack.render(matrices, mouseX - 8, mouseY - 8, delta, EmiIngredient.RENDER_ICON);
			view.pop();
			RenderSystem.applyModelViewMatrix();
		}
	}

	private static void renderCurrentTooltip(MatrixStack matrices, int mouseX, int mouseY, float delta, Screen screen) {
		ItemStack cursor = ItemStack.EMPTY;
		if (client.currentScreen instanceof HandledScreen<?> handled) {
			cursor = handled.getScreenHandler().getCursorStack();
		}
		ScreenSpace space = getHoveredSpace(mouseX, mouseY);
		if (EmiConfig.cheatMode && !cursor.isEmpty() && space != null && space.getType() == SidebarType.INDEX && EmiConfig.deleteCursorStack.isBound()) {
			List<TooltipComponent> list = List.of(
				TooltipComponent.of(EmiPort.ordered(EmiPort.translatable("emi.delete_stack"))),
				TooltipComponent.of(EmiPort.ordered(EmiConfig.deleteCursorStack.getBindText()))
			);
			if (space.rtl) {
				EmiRenderHelper.drawLeftTooltip(screen, matrices, list, mouseX, mouseY);
			} else {
				EmiRenderHelper.drawTooltip(screen, matrices, list, mouseX, mouseY);
			}
		}
		if (cursor.isEmpty() && draggedStack.isEmpty()) {
			client.getProfiler().swap("hover");
			MatrixStack view = RenderSystem.getModelViewStack();
			view.push();
			view.translate(0, 0, 200);
			RenderSystem.applyModelViewMatrix();
			EmiIngredient hov = EmiStack.EMPTY;
			SidebarType sidebar = SidebarType.NONE;
			if (getHoveredStack(mouseX, mouseY, false) instanceof SidebarEmiStackInteraction sesi) {
				hov = sesi.getStack();
				sidebar = sesi.getType();
			}
			List<TooltipComponent> list = Lists.newArrayList();
			list.addAll(hov.getTooltip());
			if (EmiApi.getRecipeContext(hov) == null && EmiConfig.showCraft.isHeld()) {
				EmiRecipe recipe = EmiUtil.getPreferredRecipe(hov, lastPlayerInventory, false);
				if (recipe != null) {
					list.add(new RecipeTooltipComponent(recipe, true));
				}
			}
			if (EmiConfig.editMode && sidebar == SidebarType.INDEX) {
				list.add(TooltipComponent.of(EmiPort.translatable("emi.edit_mode.hide_one", EmiConfig.hideStack.getBindText()).asOrderedText()));
				list.add(TooltipComponent.of(EmiPort.translatable("emi.edit_mode.hide_all", EmiConfig.hideStackById.getBindText()).asOrderedText()));
			}
			if (space != null && space.rtl) {
				EmiRenderHelper.drawLeftTooltip(screen, matrices, list, mouseX, mouseY);
			} else {
				EmiRenderHelper.drawTooltip(screen, matrices, list, mouseX, mouseY);
			}
			view.pop();
			RenderSystem.applyModelViewMatrix();
			client.getProfiler().pop();
		}
		lastStackTooltipRendered = null;
	}

	private static void renderDevMode(MatrixStack matrices, int mouseX, int mouseY, float delta, Screen screen) {
		if (EmiConfig.devMode) {
			client.getProfiler().swap("dev");
			int color = 0xFFFFFF;
			String title = "EMI Dev Mode";
			int off = -16;
			if (!EmiReloadLog.warnings.isEmpty()) {
				color = 0xFF0000;
				off = -11;
				String warnCount = EmiReloadLog.warningCount + " Warnings";
				client.textRenderer.drawWithShadow(matrices, warnCount, 48, screen.height - 21, color);
				int width = Math.max(client.textRenderer.getWidth(title), client.textRenderer.getWidth(warnCount));
				if (mouseX >= 48 && mouseX < width + 48 && mouseY > screen.height - 28) {
					screen.renderTooltip(matrices, Stream.concat(Stream.of(" EMI detected some issues, see log for full details"),
							EmiReloadLog.warnings.stream()).map(s -> {
								String a = s;
								if (a.length() > 10 && client.textRenderer.getWidth(a) > screen.width - 20) {
									a = client.textRenderer.trimToWidth(a, screen.width - 30) + "...";
								}
								return EmiPort.literal(a);
							})
							.collect(Collectors.toList()), 0, 20);
				}
			}
			client.textRenderer.drawWithShadow(matrices, title, 48, screen.height + off, color);
		}
	}

	private static void renderExclusionAreas(MatrixStack matrices, int mouseX, int mouseY, float delta, Screen screen) {
		if (EmiConfig.highlightExclusionAreas) {
			if (screen instanceof EmiScreen emi) {
				for (SidebarPanel panel : panels) {
					if (panel.isVisible()) {
						Bounds bounds = panel.getBounds();
						DrawableHelper.fill(matrices, bounds.left(), bounds.top(), bounds.right(), bounds.bottom(),
								0x440000ff);
					}
				}
				List<Bounds> exclusions = EmiExclusionAreas.getExclusion(screen);
				if (exclusions.size() == 0) {
					return;
				}
				for (int i = 0; i < exclusions.size(); i++) {
					Bounds b = exclusions.get(i);
					DrawableHelper.fill(matrices, b.x(), b.y(), b.x() + b.width(), b.y() + b.height(),
							i == 0 ? 0x4400ff00 : 0x44ff0000);
				}
			}
		}
	}

	public static void addWidgets(Screen screen) {
		forceRecalculate();
		if (EmiConfig.centerSearchBar) {
			search.x = (screen.width - 160) / 2;
			search.y = screen.height - 21;
			search.setWidth(160);
		} else {
			search.x = panels.get(1).space.tx;
			search.y = screen.height - 21;
			search.setWidth(panels.get(1).space.tw * ENTRY_SIZE);
		}
		EmiPort.focus(search, false);

		emi.x = 2;
		emi.y = screen.height - 22;

		tree.x = 24;
		tree.y = screen.height - 22;

		updateSidebarButtons();
	}

	private static void updateSidebarButtons() {
		for (SidebarPanel panel : panels) {
			panel.updateWidgetPosition();
			panel.updateWidgetVisibility();
		}
	}

	public static boolean mouseScrolled(double mouseX, double mouseY, double amount) {
		scrollAcc += amount;
		int sa = (int) scrollAcc;
		scrollAcc %= 1;
		if (isDisabled()) {
			return false;
		}
		recalculate();
		SidebarPanel panel = getHoveredPanel((int) mouseX, (int) mouseY);
		if (panel != null) {
			panel.scroll(-sa);
			return true;
		}
		return false;
	}

	public static boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (search.mouseClicked(mouseX, mouseY, button)) {
			return true;
		} else if (emi.mouseClicked(mouseX, mouseY, button)) {
			return true;
		} else if (tree.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		for (SidebarPanel panel : panels) {
			if (panel.cycle.mouseClicked(mouseX, mouseY, button)) {
				return true;
			} else if (panel.pageLeft.mouseClicked(mouseX, mouseY, button)) {
				return true;
			} else if (panel.pageRight.mouseClicked(mouseX, mouseY, button)) {
				return true;
			}
		}
		if (isDisabled()) {
			if (EmiConfig.toggleVisibility.matchesMouse(button)) {
				toggleVisibility(true);
				return true;
			}
			return false;
		}
		recalculate();
		EmiIngredient ingredient = getHoveredStack((int) mouseX, (int) mouseY, false).getStack();
		pressedStack = ingredient;
		if (!ingredient.isEmpty()) {
			return true;
		} else {
			if (genericInteraction(bind -> bind.matchesMouse(button))) {
				return true;
			}
		}
		return false;
	}

	public static boolean mouseReleased(double mouseX, double mouseY, int button) {
		try {
			if (isDisabled()) {
				return false;
			}
			int mx = (int) mouseX;
			int my = (int) mouseY;
			recalculate();
			if (EmiConfig.cheatMode && EmiConfig.deleteCursorStack.matchesMouse(button)) {
				if (deleteCursor(mx, my)) {
					// Returning false here makes the handled screen do something and removes a bug, oh well.
					return false;
				}
			}
			SidebarPanel panel = getHoveredPanel(mx, my);
			if (draggedStack == EmiStack.EMPTY && panel != null && panel.getType() == SidebarType.CHESS) {
				EmiChess.interact(pressedStack, button);
				return true;
			}
			if (!pressedStack.isEmpty()) {
				if (!draggedStack.isEmpty()) {
					if (panel != null) {
						ScreenSpace space = panel.getHoveredSpace(mx, my);
						if (space != null && space.getType() == SidebarType.FAVORITES ) {
							int page = panel.page;
							int pageSize = space.pageSize;
							int index = Math.min(space.getClosestEdge(mx, my), EmiFavorites.favorites.size());
							if (index + pageSize * page > EmiFavorites.favorites.size()) {
								index = EmiFavorites.favorites.size() - pageSize * page;
							}
							if (index >= 0) {
								EmiFavorites.addFavoriteAt(draggedStack, index + pageSize * page);
								space.batcher.repopulate();
							}
							return true;
						} else if (panel.getType() == SidebarType.CHESS) {
							EmiChess.drop(draggedStack, getHoveredStack(mx, my, true).getStack());
						}
					} else if (client.currentScreen != null) {
						if (EmiDragDropHandlers.dropStack(client.currentScreen, draggedStack, mx, my)) {
							return true;
						}
					}
				} else {
					EmiStackInteraction hovered = getHoveredStack((int) mouseX, (int) mouseY, false);
					if (draggedStack.isEmpty() && stackInteraction(hovered, bind -> bind.matchesMouse(button))) {
						return true;
					}
				}
				if (genericInteraction(bind -> bind.matchesMouse(button))) {
					return true;
				}
			}
			return false;
		} finally {
			pressedStack = EmiStack.EMPTY;
			draggedStack = EmiStack.EMPTY;
		}
	}

	public static boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
		if (isDisabled()) {
			return false;
		}
		if (draggedStack.isEmpty() && button == 0) {
			if (client.currentScreen instanceof HandledScreen<?> handled) {
				if (!handled.getScreenHandler().getCursorStack().isEmpty()) {
					return false;
				}
			}
			recalculate();
			EmiStackInteraction hovered = getHoveredStack((int) mouseX, (int) mouseY, false);
			if (hovered.getStack() != pressedStack && !(pressedStack instanceof EmiFavorite.Synthetic)) {
				draggedStack = pressedStack;
			}
		}
		return false;
	}

	public static boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (isDisabled()) {
			if (EmiConfig.toggleVisibility.matchesKey(keyCode, scanCode)) {
				toggleVisibility(true);
				return true;
			}
			return false;
		}
		if (EmiScreenManager.search.keyPressed(keyCode, scanCode, modifiers) || EmiScreenManager.search.isActive()) {
			return true;
		}
		if (hasFocusedTextField(client.currentScreen, 10)) {
			return false;
		}
		if (EmiConfig.cheatMode && EmiConfig.deleteCursorStack.matchesKey(keyCode, scanCode)) {
			if (deleteCursor(lastMouseX, lastMouseY)) {
				return true;
			}
		}
		if (EmiInput.isControlDown() && keyCode == GLFW.GLFW_KEY_Y) {
			EmiApi.displayAllRecipes();
			return true;
		} else {
			recalculate();
			if (stackInteraction(getHoveredStack(lastMouseX, lastMouseY, true),
					bind -> bind.matchesKey(keyCode, scanCode))) {
				return true;
			}
			if (genericInteraction(bind -> bind.matchesKey(keyCode, scanCode))) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasFocusedTextField(ParentElement parent, int depthBail) {
		if (depthBail <= 0) {
			return false;
		}
		for (Element e : client.currentScreen.children()) {
			if (e instanceof TextFieldWidget tfw && tfw.isActive() && tfw.visible) {
				return true;
			} else if (e instanceof ParentElement p) {
				return hasFocusedTextField(p, depthBail - 1);
			}
		}
		return false;
	}

	public static boolean genericInteraction(Function<EmiBind, Boolean> function) {
		if (function.apply(EmiConfig.toggleVisibility)) {
			toggleVisibility(true);
			return true;
		}
		boolean searchBreak = false;
		if (function.apply(EmiConfig.focusSearch)) {
			if (client.currentScreen != null) {
				client.currentScreen.setFocused(search);
				EmiPort.focus(search, true);
				searchBreak = true;
			}
		}
		if (function.apply(EmiConfig.clearSearch)) {
			search.setText("");
			searchBreak = true;
		}
		if (searchBreak) {
			return true;
		}
		if (function.apply(EmiConfig.viewTree)) {
			EmiApi.viewRecipeTree();
			return true;
		} else if (function.apply(EmiConfig.back)) {
			if (!EmiHistory.isEmpty()) {
				EmiHistory.pop();
				return true;
			}
		}
		return false;
	}

	public static boolean stackInteraction(EmiStackInteraction stack, Function<EmiBind, Boolean> function) {
		EmiIngredient ingredient = stack.getStack();
		EmiRecipe context = EmiApi.getRecipeContext(ingredient);
		if (!ingredient.isEmpty()) {
			if (EmiConfig.editMode) {
				if (stack instanceof SidebarEmiStackInteraction sesi && sesi.getType() == SidebarType.INDEX) {
					if (function.apply(EmiConfig.hideStack)) {
						EmiHidden.setVisibility(sesi.getStack(), !EmiHidden.isHidden(sesi.getStack()), false);
						return true;
					} else if (function.apply(EmiConfig.hideStackById)) {
						EmiHidden.setVisibility(sesi.getStack(), !EmiHidden.isHidden(sesi.getStack()), true);
						return true;
					}
				}
			}
			if (craftInteraction(ingredient, () -> context, stack, function)) {
				return true;
			}
			if (EmiConfig.cheatMode) {
				if (ingredient.getEmiStacks().size() == 1 && stack instanceof SidebarEmiStackInteraction) {
					if (function.apply(EmiConfig.cheatOneToInventory)) {
						return give(ingredient.getEmiStacks().get(0), 1, 0);
					} else if (function.apply(EmiConfig.cheatStackToInventory)) {
						return give(ingredient.getEmiStacks().get(0),
								ingredient.getEmiStacks().get(0).getItemStack().getMaxCount(), 0);
					} else if (function.apply(EmiConfig.cheatOneToCursor)) {
						return give(ingredient.getEmiStacks().get(0), 1, 1);
					} else if (function.apply(EmiConfig.cheatStackToCursor)) {
						return give(ingredient.getEmiStacks().get(0),
								ingredient.getEmiStacks().get(0).getItemStack().getMaxCount(), 1);
					}
				}
			}
			if (function.apply(EmiConfig.viewRecipes)) {
				EmiApi.displayRecipes(ingredient);
				if (stack.getRecipeContext() != null) {
					EmiApi.focusRecipe(stack.getRecipeContext());
				}
				return true;
			} else if (function.apply(EmiConfig.viewUses)) {
				EmiApi.displayUses(ingredient);
				return true;
			} else if (function.apply(EmiConfig.favorite)) {
				EmiFavorites.addFavorite(ingredient, stack.getRecipeContext());
				repopulatePanels(SidebarType.FAVORITES);
				return true;
			} else if (function.apply(EmiConfig.viewStackTree) && stack.getRecipeContext() != null) {
				BoM.setGoal(stack.getRecipeContext());
				EmiApi.viewRecipeTree();
				return true;
			}
			Supplier<EmiRecipe> supplier = () -> {
				return EmiUtil.getPreferredRecipe(ingredient, lastPlayerInventory, true);
			};
			if (craftInteraction(ingredient, supplier, stack, function)) {
				return true;
			}
		}
		return false;
	}

	private static boolean craftInteraction(EmiIngredient ingredient, Supplier<EmiRecipe> contextSupplier,
			EmiStackInteraction stack, Function<EmiBind, Boolean> function) {
		if (!(stack instanceof SidebarEmiStackInteraction)) {
			return false;
		}
		EmiFillAction action = null;
		boolean all = false;
		if (function.apply(EmiConfig.craftAllToInventory)) {
			action = EmiFillAction.QUICK_MOVE;
			all = true;
		} else if (function.apply(EmiConfig.craftOneToInventory)) {
			action = EmiFillAction.QUICK_MOVE;
		} else if (function.apply(EmiConfig.craftOneToCursor)) {
			action = EmiFillAction.CURSOR;
		} else if (function.apply(EmiConfig.craftAll)) {
			action = EmiFillAction.FILL;
			all = true;
		} else if (function.apply(EmiConfig.craftOne)) {
			action = EmiFillAction.FILL;
		}
		if (action != null) {
			EmiRecipe context = contextSupplier.get();
			if (context != null) {
				if (EmiConfig.miscraftPrevention) {
					ScreenSpace space = getHoveredSpace(lastMouseX, lastMouseY);
					if (space != null && (space.getType() == SidebarType.CRAFTABLES || space.getType() == SidebarType.CRAFT_HISTORY)) {
						lastHoveredCraftableOffset = space.getRawOffsetFromMouse(lastMouseX, lastMouseY);
						if (lastHoveredCraftableOffset != -1) {
							lastHoveredCraftableSturdy = lastHoveredCraftable != null;
							lastHoveredCraftable = stack;
							if (!all) {
								lastHoveredCraftableSturdy = true;
							}
						}
					}
				}
				int amount = all ? Integer.MAX_VALUE : 1;
				if (stack.getStack() instanceof EmiFavorite.Synthetic syn) {
					int batches = (int) syn.batches;
					if (syn.getRecipe() == null) {
						int oc = EmiUtil.getOutputCount(context, syn.getStack());
						if (oc > 0) {
							batches /= oc;
						}
					}
					amount = Math.min(amount, batches);
				}
				if (EmiRecipeFiller.performFill(context, EmiApi.getHandledScreen(), action, amount)) {
					MinecraftClient.getInstance().getSoundManager()
							.play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0f));
					return true;
				}
			}
		}
		return false;
	}

	public static void toggleVisibility(boolean notify) {
		EmiConfig.enabled = !EmiConfig.enabled;
		EmiConfig.writeConfig();
		if (notify && !EmiConfig.enabled && EmiConfig.helpLevel.has(HelpLevel.NORMAL)) {
			client.getToastManager().add(new DisabledToast());
		}
	}

	private static boolean give(EmiStack stack, int amount, int mode) {
		if (stack.getItemStack().isEmpty()) {
			return false;
		}
		ItemStack is = stack.getItemStack().copy();
		is.setCount(amount);
		if (mode == 1 && client.player.getAbilities().creativeMode && client.currentScreen instanceof CreativeInventoryScreen) {
			client.player.currentScreenHandler.setCursorStack(is);
			return true;
		}
		if (EmiClient.onServer) {
			EmiNetwork.sendToServer(new CreateItemC2SPacket(mode, is));
			return true;
		} else {
			if (!is.isEmpty()) {
				Identifier id = EmiPort.getItemRegistry().getId(is.getItem());
				String command = "give @s " + id;
				if (is.hasNbt()) {
					command += is.getNbt().toString();
				}
				command += " " + amount;
				client.player.networkHandler.sendChatCommand(command);
				return true;
			}
			return false;
		}
	}
	
	private static boolean deleteCursor(int mx, int my) {
		if (client.currentScreen instanceof HandledScreen<?> handled) {
			ItemStack cursor = handled.getScreenHandler().getCursorStack();
			ScreenSpace space = getHoveredSpace(mx, my);
			if (!cursor.isEmpty() && space != null && space.getType() == SidebarType.INDEX) {
				handled.getScreenHandler().setCursorStack(ItemStack.EMPTY);
				EmiNetwork.sendToServer(new CreateItemC2SPacket(1, ItemStack.EMPTY));
				return true;
			}
		}
		return false;
	}

	public static class SidebarPanel {
		public final SizedButtonWidget pageLeft, pageRight;
		public final SidebarButtonWidget cycle;
		public final SidebarPages pages;
		public final SidebarSide side;
		public List<ScreenSpace> spaces;
		public ScreenSpace space;
		public SidebarTheme theme;
		public boolean header;
		public int sidebarPage;
		public int page;

		public SidebarPanel(SidebarSide side, SidebarPages pages) {
			this.side = side;
			this.pages = pages;
			pageLeft = new SizedButtonWidget(0, 0, 16, 16, 224, 0, this::hasMultiplePages, (w) -> scroll(-1));
			pageRight = new SizedButtonWidget(0, 0, 16, 16, 240, 0, this::hasMultiplePages, (w) -> scroll(1));
			cycle = new SidebarButtonWidget(0, 0, 16, 16, this);
		}

		public void setSpaces(ScreenSpace main, List<ScreenSpace> subpanels) {
			space = main;
			spaces = Stream.concat(Stream.of(main), subpanels.stream()).toList();
		}

		public List<ScreenSpace> getSpaces() {
			if (spaces == null) {
				return List.of();
			}
			return spaces;
		}

		public ScreenSpace getHoveredSpace(int mouseX, int mouseY) {
			for (ScreenSpace space : getSpaces()) {
				if (space.containsNotExcluded(mouseX, mouseY)) {
					return space;
				}
			}
			return null;
		}

		public SidebarType getType() {
			if (sidebarPage >= 0 && sidebarPage < pages.pages.size()) {
				return pages.pages.get(sidebarPage).type;
			}
			return SidebarType.NONE;
		}

		public boolean supportsType(SidebarType type) {
			for (SidebarPages.SidebarPage page : pages.pages) {
				if (page.type == type) {
					return true;
				}
			}
			return false;
		}

		public void setSidebarPage(int page) {
			if (page == sidebarPage) {
				return;
			}
			boolean forceRecalculate = getType() == SidebarType.CHESS;
			this.sidebarPage = page;
			forceRecalculate |= getType() == SidebarType.CHESS;
			if (forceRecalculate) {
				if (client.currentScreen != null) {
					// Force recalculation
					lastWidth = -1;
					recalculate();
				}
			}
			if (isSearch()) {
				EmiSearch.search(search.getText());
			}
			if (space != null) {
				space.batcher.repopulate();
			}
		}

		public void setType(SidebarType type) {
			for (int i = 0; i < pages.pages.size(); i++) {
				SidebarPages.SidebarPage page = pages.pages.get(i);
				if (page.type == type) {
					setSidebarPage(i);
				}
			}
		}

		public void cycleType(int amount) {
			int page = sidebarPage + amount;
			if (page >= pages.pages.size()) {
				page = 0;
			} else if (page < 0) {
				page = Math.max(pages.pages.size() - 1, 0);
			}
			setSidebarPage(page);
		}

		public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
			cycleType(0);
			if (getType() == SidebarType.CHESS) {
				EmiChess.get().update();
				if (space.tw != 8 || space.th != 8) {
					cycleType(1);
				}
			}
			if (isVisible()) {
				client.getProfiler().swap(side.getName());
				matrices.push();
				matrices.translate(0, 0, 100);
				pageLeft.render(matrices, mouseX, mouseY, delta);
				cycle.render(matrices, mouseX, mouseY, delta);
				pageRight.render(matrices, mouseX, mouseY, delta);
				matrices.pop();
				int totalPages = (space.getStacks().size() - 1) / space.pageSize + 1;
				wrapPage();
				drawHeader(matrices, mouseX, mouseY, delta, page, totalPages);
				for (ScreenSpace space : getSpaces()) {
					if (space == this.space) {
						space.render(matrices, mouseX, mouseY, delta, space.pageSize * page);
					} else {
						space.render(matrices, mouseX, mouseY, delta, 0);
					}
				}
			}
		}

		private void drawBackground(MatrixStack matrices, int mouseX, int mouseY, float delta) {
			cycleType(0);
			if (getType() == SidebarType.CHESS) {
				if (space.tw != 8 || space.th != 8) {
					cycleType(1);
				}
			}
			if (isVisible()) {
				RenderSystem.enableDepthTest();
				EmiPort.setPositionTexShader();
				RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
				int headerOffset = header ? 18 : 0;
				if (theme == SidebarTheme.VANILLA) {
					int totalHeight = 18 + headerOffset;
					for (ScreenSpace space : getSpaces()) {
						totalHeight += space.th * ENTRY_SIZE + SUBPANEL_SEPARATOR_SIZE;
					}
					RenderSystem.setShaderTexture(0, EmiRenderHelper.BACKGROUND);
					EmiRenderHelper.drawNinePatch(matrices, space.tx - 9, space.ty - 9 - headerOffset,
						space.tw * ENTRY_SIZE + 18, totalHeight, 0, 32, 8, 1);
				} else if (theme == SidebarTheme.MODERN) {
					int offset = 2;
					for (ScreenSpace space : getSpaces()) {
						RenderSystem.setShaderTexture(0, EmiRenderHelper.GRID);
						RenderSystem.enableBlend();
						DrawableHelper.drawTexture(matrices, space.tx, space.ty, space.tw * ENTRY_SIZE,
							space.th * ENTRY_SIZE, 0, 0, space.tw, space.th, 2, offset);
						if (space.th % 2 == 1) {
							offset *= -1;
						}
						RenderSystem.disableBlend();
					}
				}
				for (ScreenSpace space : getSpaces()) {
					if (space != this.space) {
						RenderSystem.setShaderTexture(0, EmiRenderHelper.DASH);
						DrawableHelper.drawTexture(matrices, space.tx + 1, space.ty - 2, space.tw * ENTRY_SIZE, 1, 0, 0, space.tw * ENTRY_SIZE, 1, 6, 1);
					}
				}
			}
		}

		private void drawHeader(MatrixStack matrices, int mouseX, int mouseY, float delta, int page, int totalPages) {
			if (header) {
				Text text = EmiRenderHelper.getPageText(page + 1, totalPages, (space.tw - 3) * ENTRY_SIZE);
				int x = space.tx + (space.tw * ENTRY_SIZE) / 2;
				int maxLeft = (space.tw - 2) * ENTRY_SIZE / 2 - ENTRY_SIZE;
				int w = client.textRenderer.getWidth(text) / 2;
				if (w > maxLeft) {
					x += (w - maxLeft);
				}
				EmiPort.drawCenteredText(matrices, client.textRenderer, text, x, space.ty - 15, 0xFFFFFF);
				if (totalPages > 1 && space.tw > 2) {
					int scrollLeft = space.tx + 18;
					int scrollWidth = space.tw * ENTRY_SIZE - 36;
					int scrollY = space.ty - 4;
					DrawableHelper.fill(matrices, scrollLeft, scrollY, scrollLeft + scrollWidth, scrollY + 2,
							0x55555555);
					EmiRenderHelper.drawScroll(matrices, scrollLeft, scrollY, scrollWidth, 2, page, totalPages, 0xFFFFFFFF);
				}
			}
		}

		private void wrapPage() {
			int totalPages = (space.getStacks().size() - 1) / space.pageSize + 1;
			if (page >= totalPages) {
				page = 0;
				space.batcher.repopulate();
			} else if (page < 0) {
				page = totalPages - 1;
				space.batcher.repopulate();
			}
		}

		public boolean isSearch() {
			return side == SidebarSide.RIGHT;
		}

		public void updateWidgetPosition() {
			pageLeft.x = space.tx;
			pageLeft.y = space.ty - 18;
			pageRight.x = space.tx + space.tw * ENTRY_SIZE - 16;
			pageRight.y = pageLeft.y;
			cycle.x = space.tx + 18;
			cycle.y = pageLeft.y - 1;
		}

		public boolean isVisible() {
			if (getType() == SidebarType.CHESS && (space.tw != 8 || space.th != 8)) {
				return false;
			}
			return !isDisabled() && space.pageSize > 0 && pages.pages.size() > 0;
		}

		public void updateWidgetVisibility() {
			boolean visible = header && isVisible();

			pageLeft.visible = visible;
			cycle.visible = visible;
			pageRight.visible = visible;
		}

		public boolean hasMultiplePages() {
			return space.getStacks().size() > space.pageSize;
		}

		public void scroll(int delta) {
			if (space.pageSize == 0) {
				return;
			}
			page += delta;
			int pageSize = space.pageSize;
			int totalPages = (space.getStacks().size() - 1) / pageSize + 1;
			if (totalPages <= 1) {
				return;
			}
			if (page >= totalPages) {
				page = 0;
			} else if (page < 0) {
				page = totalPages - 1;
			}
			space.batcher.repopulate();
		}

		public Bounds getBounds() {
			int headerOffset = (header ? 18 : 0);
			ScreenSpace end = getSpaces().get(getSpaces().size() - 1);
			return new Bounds(
					space.tx - theme.horizontalPadding,
					space.ty - theme.verticalPadding - headerOffset,
					space.tw * ENTRY_SIZE + theme.horizontalPadding * 2,
					(end.ty - space.ty) + end.th * ENTRY_SIZE + theme.verticalPadding * 2 + headerOffset);
		}
	}

	public static class ScreenSpace {
		public final StackBatcher batcher = batchers.claim();
		private final Supplier<SidebarType> typeSupplier;
		public final int tx, ty, tw, th;
		public final int pageSize;
		public final boolean rtl;
		public final int[] widths;
		public final boolean search;

		public ScreenSpace(int tx, int ty, int tw, int th, boolean rtl, List<Bounds> exclusion, Supplier<SidebarType> typeSupplier, boolean search) {
			this.tx = tx;
			this.ty = ty;
			this.tw = tw;
			this.th = th;
			this.rtl = rtl;
			this.typeSupplier = typeSupplier;
			this.search = search;
			int[] widths = new int[th];
			int pageSize = 0;
			for (int y = 0; y < th; y++) {
				int width = 0;
				int cy = ty + y * ENTRY_SIZE;
				outer: for (int x = 0; x < tw; x++) {
					int cx = tx + (rtl ? (tw - 1 - x) : x) * ENTRY_SIZE;
					int rx = cx + ENTRY_SIZE - 1;
					int ry = cy + ENTRY_SIZE - 1;
					for (Bounds rect : exclusion) {
						if (rect.contains(cx, cy) || rect.contains(rx, cy) || rect.contains(cx, ry)
								|| rect.contains(rx, ry)) {
							break outer;
						}
					}
					width++;
				}
				widths[y] = width;
				pageSize += width;
			}
			this.pageSize = pageSize;
			this.widths = widths;
		}

		public List<? extends EmiIngredient> getStacks() {
			if (search && getType() != SidebarType.CHESS) {
				return searchedStacks;
			} else {
				return EmiSidebars.getStacks(getType());
			}
		}

		public List<? extends EmiIngredient> getPage(int page) {
			List<? extends EmiIngredient> stacks = getStacks();
			int start = page * pageSize;
			int end = Math.min(start + pageSize, stacks.size());
			if (end > start) {
				return stacks.subList(start, end);
			}
			return List.of();
		}

		public SidebarType getType() {
			return typeSupplier.get();
		}

		public void render(MatrixStack matrices, int mouseX, int mouseY, float delta, int startIndex) {
			if (this.pageSize > 0) {
				RenderSystem.enableDepthTest();
				EmiPort.setPositionTexShader();
				RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
				int hx = -1, hy = -1;
				batcher.begin(0, 0, 0);
				int i = startIndex;
				List<? extends EmiIngredient> stacks = getStacks();
				int hovered = this.getRawOffsetFromMouse(mouseX, mouseY);
				if (hovered != -1 && EmiConfig.showHoverOverlay && startIndex + hovered < stacks.size()) {
					hx = this.getRawX(hovered);
					hy = this.getRawY(hovered);
					EmiRenderHelper.drawSlotHightlight(matrices, hx, hy, ENTRY_SIZE, ENTRY_SIZE);
				}
				matrices.push();
				matrices.translate(0, 0, 100);
				outer: for (int yo = 0; yo < this.th; yo++) {
					for (int xo = 0; xo < this.getWidth(yo); xo++) {
						if (i >= stacks.size()) {
							break outer;
						}
						int cx = this.getX(xo, yo);
						int cy = this.getY(xo, yo);
						EmiIngredient stack = stacks.get(i++);
						batcher.render(stack, matrices, cx + 1, cy + 1, delta);
						if (getType() == SidebarType.INDEX) {
							if (EmiConfig.editMode) {
								if (EmiHidden.isHidden(stack)) {
									RenderSystem.enableDepthTest();
									DrawableHelper.fill(matrices, cx, cy, cx + ENTRY_SIZE, cy + ENTRY_SIZE, 0x33ff0000);
								}
							} else if (EmiConfig.highlightDefaulted) {
								if (BoM.getRecipe(stack) != null) {
									RenderSystem.enableDepthTest();
									DrawableHelper.fill(matrices, cx, cy, cx + ENTRY_SIZE, cy + ENTRY_SIZE, 0x3300ff00);
								}
							}
						}
					}
				}
				batcher.draw();
				matrices.pop();
			}
		}

		public int getWidth(int y) {
			return widths[y];
		}

		public int getX(int x, int y) {
			return tx + (rtl ? x + tw - getWidth(y) : x) * ENTRY_SIZE;
		}

		public int getY(int x, int y) {
			return ty + y * ENTRY_SIZE;
		}

		public int getEdgeX(int off) {
			int t = 0;
			int y = 0;
			while (y < th && t + getWidth(y) < off) {
				t += getWidth(y++);
			}
			return getX(off - t, y);
		}

		public int getEdgeY(int off) {
			int t = 0;
			int y = 0;
			while (y < th && t + getWidth(y) < off) {
				t += getWidth(y++);
			}
			return ty + y * ENTRY_SIZE;
		}

		public int getRawX(int off) {
			int t = 0;
			int y = 0;
			while (y < th && t + getWidth(y) <= off) {
				t += getWidth(y++);
			}
			return getX(off - t, y);
		}

		public int getRawY(int off) {
			int t = 0;
			int y = 0;
			while (y < th && t + getWidth(y) <= off) {
				t += getWidth(y++);
			}
			return ty + y * ENTRY_SIZE;
		}

		public int getClosestEdge(int x, int y) {
			if (y < ty) {
				return 0;
			} else if (y >= ty + th * ENTRY_SIZE) {
				return pageSize;
			} else {
				x = (x - tx) / ENTRY_SIZE;
				y = (y - ty) / ENTRY_SIZE;
				int off = 0;
				for (int i = 0; i < y; i++) {
					off += widths[i];
				}
				if (x < 0) {
					return y;
				} else if (x >= widths[y]) {
					return y + widths[y];
				}
				if (rtl) {
					int to = tw - widths[y];
					if (x >= to) {
						off += x - to;
					}
				} else {
					if (x < widths[y]) {
						off += x;
					} else {
						off += widths[y];
					}
				}
				return off;
			}
		}

		public int getRawOffsetFromMouse(int mouseX, int mouseY) {
			if (mouseX < tx || mouseY < ty) {
				return -1;
			}
			return getRawOffset((mouseX - tx) / ENTRY_SIZE, (mouseY - ty) / ENTRY_SIZE);
		}

		public int getRawOffset(int x, int y) {
			if (x >= 0 && y >= 0 && x < tw && y < th) {
				int off = 0;
				for (int i = 0; i < y; i++) {
					off += widths[i];
				}
				if (rtl) {
					int to = tw - widths[y];
					if (x >= to) {
						return off + x - to;
					}
				} else {
					if (x < widths[y]) {
						return off + x;
					}
				}
			}
			return -1;
		}

		public boolean contains(int x, int y) {
			return x >= tx && x < tx + tw * ENTRY_SIZE && y >= ty && y < ty + th * ENTRY_SIZE;
		}

		public boolean containsNotExcluded(int x, int y) {
			if (this.contains(lastMouseX, lastMouseY)) {
				for (Bounds bounds : EmiExclusionAreas.getExclusion(client.currentScreen)) {
					if (bounds.contains(x, y)) {
						return false;
					}
				}
				return true;
			}
			return false;
		}
	}

	public static class SidebarEmiStackInteraction extends EmiStackInteraction {
		public final ScreenSpace space;

		public SidebarEmiStackInteraction(EmiIngredient stack, ScreenSpace space) {
			super(stack);
			this.space = space;
		}

		public SidebarEmiStackInteraction(EmiIngredient stack, ScreenSpace space, EmiRecipe recipe, boolean clickable) {
			super(stack, recipe, clickable);
			this.space = space;
		}

		public SidebarType getType() {
			return space.getType();
		}
	}
}
