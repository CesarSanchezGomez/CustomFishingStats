package CesarCosmico.actions;

import java.util.Objects;

public class FishingStatContext {
    private final String type;
    private final String category;
    private final String item;
    private final int amount;

    private FishingStatContext(Builder builder) {
        this.type = builder.type;
        this.category = builder.category;
        this.item = builder.item;
        this.amount = builder.amount;
    }

    public String getType() {
        return type;
    }

    public String getCategory() {
        return category;
    }

    public String getItem() {
        return item;
    }

    public int getAmount() {
        return amount;
    }

    public boolean hasItem() {
        return item != null && !item.isEmpty();
    }

    public static class Builder {
        private String type;
        private String category;
        private String item;
        private int amount = 1;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder item(String item) {
            this.item = item;
            return this;
        }

        public Builder amount(int amount) {
            this.amount = amount;
            return this;
        }

        public FishingStatContext build() {
            Objects.requireNonNull(type, "Type cannot be null");
            Objects.requireNonNull(category, "Category cannot be null");
            return new FishingStatContext(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        if (hasItem()) {
            return String.format("FishingStatContext{type=%s, category=%s, item=%s, amount=%d}",
                    type, category, item, amount);
        }
        return String.format("FishingStatContext{type=%s, category=%s, amount=%d (generic points)}",
                type, category, amount);
    }
}