package online.devliving.stepbuilder.generator;

public class SelectorOption {
    private final StepBuilderOption option;
    private final String caption;
    private final char mnemonic;
    private final String toolTip; //optional

    private SelectorOption(final Builder builder) {
        option = builder.option;
        caption = builder.caption;
        mnemonic = builder.mnemonic;
        toolTip = builder.toolTip;
    }

    public static IOption newBuilder() {
        return new Builder();
    }

    public StepBuilderOption getOption() {
        return option;
    }

    public String getCaption() {
        return caption;
    }

    public char getMnemonic() {
        return mnemonic;
    }

    public String getToolTip() {
        return toolTip;
    }

    interface IOption{
        ICaption withOption(StepBuilderOption option);
    }

    interface ICaption{
        IMnemonic withCaption(String caption);
    }

    interface IMnemonic{
        IBuild withMnemonic(char mnemonic);
    }

    interface IBuild{
        IBuild withTooltip(String tooltip);
        SelectorOption build();
    }

    public static final class Builder implements IOption, ICaption, IMnemonic, IBuild{
        private StepBuilderOption option;
        private String caption;
        private char mnemonic;
        private String toolTip;

        private Builder() { }

        public ICaption withOption(final StepBuilderOption option) {
            this.option = option;
            return this;
        }

        public IMnemonic withCaption(final String caption) {
            this.caption = caption;
            return this;
        }

        public IBuild withMnemonic(final char mnemonic) {
            this.mnemonic = mnemonic;
            return this;
        }

        public IBuild withTooltip(final String toolTip) {
            this.toolTip = toolTip;
            return this;
        }

        public SelectorOption build() {
            return new SelectorOption(this);
        }
    }
}
