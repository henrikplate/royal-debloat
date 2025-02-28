package se.kth.jdbl.tree;

/**
 * Generates an output that is identical to the output generated by the following Maven command:
 * <pre>mvn dependency:tree -DoutputType=text -Dtokens=standard</pre>
 */
public class StandardTextVisitor extends AbstractTextVisitor {

    @Override
    public String getTreeSymbols(Node node) {
        if (node == node.getParent().getLastChildNode()) {
            return "\\- ";
        } else {
            return "+- ";
        }
    }

    @Override
    public String getParentTreeSymbols(Node node) {
        if (node == node.getParent().getLastChildNode()) {
            return "   ";
        } else {
            return "|  ";
        }
    }

}
