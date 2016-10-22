/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.util;

import freemarker.ext.beans.BeansWrapper;
import freemarker.ext.beans.BeansWrapperBuilder;
import freemarker.template.*;

import org.moqui.BaseException;
import org.moqui.impl.context.renderer.FtlTemplateRenderer;
import org.moqui.util.MNode;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class FtlNodeWrapper implements TemplateNodeModel, TemplateSequenceModel, TemplateHashModelEx,
        AdapterTemplateModel, TemplateScalarModel {
    protected final static Logger logger = LoggerFactory.getLogger(FtlNodeWrapper.class);
    private final static BeansWrapper wrapper = new BeansWrapperBuilder(FtlTemplateRenderer.FTL_VERSION).build();

    /** Factory method for null-sensitive Node wrapping. */
    // public static FtlNodeWrapper wrapNode(Node groovyNode) { return groovyNode != null ? new FtlNodeWrapper(new MNode(groovyNode)) : null }
    public static FtlNodeWrapper wrapNode(MNode mNode) { return mNode != null ? new FtlNodeWrapper(mNode) : null; }
    // public static FtlNodeWrapper makeFromText(String location, String xmlText) { return wrapNode(MNode.parseText(location, xmlText)); }

    private MNode mNode;
    private FtlNodeWrapper parentNode = null;
    private FtlNodeListWrapper allChildren = null;

    private final HashMap<String, TemplateModel> attrAndChildrenByName = new HashMap<>();
    private static final FtlNodeListWrapper emptyNodeListWrapper = new FtlNodeListWrapper(new ArrayList<>(), null);

    private FtlNodeWrapper(MNode wrapNode) {
        this.mNode = wrapNode;
        wrapDetails();
    }
    private FtlNodeWrapper(MNode wrapNode, FtlNodeWrapper parentNode) {
        this.mNode = wrapNode;
        this.parentNode = parentNode;
        wrapDetails();
    }
    private void wrapDetails() {
        // add all attributes
        for (Map.Entry<String, String> attrEntry: mNode.getAttributes().entrySet()) {
            String attrName = attrEntry.getKey();
            String attrValue = attrEntry.getValue();
            if (attrValue != null) attrAndChildrenByName.put("@" + attrName, new FtlAttributeWrapper(attrName, attrValue, this));
        }
        // add all sub-nodes
        for (Map.Entry<String, ArrayList<MNode>> childrenEntry: mNode.getChildrenByName().entrySet())
            attrAndChildrenByName.put(childrenEntry.getKey(), new FtlNodeListWrapper(childrenEntry.getValue(), this));
    }

    public MNode getMNode() { return mNode; }
    public Object getAdaptedObject(Class aClass) { return mNode; }

    // TemplateHashModel methods
    @Override public TemplateModel get(String s) {
        if (s == null) return null;
        // first try the attribute and children caches, then if not found in either pick it apart and create what is needed
        TemplateModel attrOrChildWrapper = attrAndChildrenByName.get(s);
        if (attrOrChildWrapper != null) return attrOrChildWrapper;

        // at this point we got a null value but attributes and child nodes were pre-loaded so return null or empty list
        if (s.startsWith("@")) {
            if ("@@text".equals(s)) {
                // if we got this once will get it again so add @@text always, always want wrapper though may be null
                FtlTextWrapper textWrapper = new FtlTextWrapper(mNode.getText(), this);
                attrAndChildrenByName.put("@@text", textWrapper);
                return textWrapper;
                // TODO: handle other special hashes? (see http://www.freemarker.org/docs/xgui_imperative_formal.html)
            } else {
                return null;
            }
        } else {
            return emptyNodeListWrapper;
        }
    }
    @Override public boolean isEmpty() {
        return mNode.getAttributes().isEmpty() && mNode.getChildren().isEmpty() && (mNode.getText() == null || mNode.getText().length() == 0);
    }

    // TemplateHashModelEx methods
    @Override public TemplateCollectionModel keys() throws TemplateModelException {
        return new SimpleCollection(mNode.getAttributes().keySet(), wrapper);
    }
    @Override public TemplateCollectionModel values() throws TemplateModelException {
        return new SimpleCollection(mNode.getAttributes().values(), wrapper);
    }

    // TemplateNodeModel methods

    @Override public TemplateNodeModel getParentNode() { return getParentNodeWrapper(); }
    public FtlNodeWrapper getParentNodeWrapper() {
        if (parentNode != null) return parentNode;
        parentNode = wrapNode(mNode.getParent());
        return parentNode;
    }
    @Override public TemplateSequenceModel getChildNodes() { return this; }
    @Override public String getNodeName() { return mNode.getName(); }
    @Override public String getNodeType() { return "element"; }

    /** Namespace not supported for now. */
    @Override public String getNodeNamespace() { return null; }

    // TemplateSequenceModel methods
    @Override public TemplateModel get(int i) { return getSequenceList().get(i); }
    @Override public int size() { return getSequenceList().size(); }
    private FtlNodeListWrapper getSequenceList() {
        // Looks like attributes should NOT go in the FTL children list, so just use the node.children()
        if (allChildren == null) allChildren = (mNode.getText() != null && mNode.getText().length() > 0) ?
            new FtlNodeListWrapper(mNode.getText(), this) : new FtlNodeListWrapper(mNode.getChildren(), this);
        return allChildren;
    }

    // TemplateScalarModel methods
    @Override public String getAsString() { return mNode.getText() != null ? mNode.getText() : ""; }
    @Override public String toString() { return prettyPrintNode(mNode); }
    public static String prettyPrintNode(MNode nd) { return nd.toString(); }

    public static class FtlAttributeWrapper implements TemplateNodeModel, TemplateSequenceModel, AdapterTemplateModel,
            TemplateScalarModel {
        protected String key;
        protected String value;
        FtlNodeWrapper parentNode;
        FtlAttributeWrapper(String key, String value, FtlNodeWrapper parentNode) {
            this.key = key;
            this.value = value;
            this.parentNode = parentNode;
        }

        @Override public Object getAdaptedObject(Class aClass) { return value; }

        // TemplateNodeModel methods
        @Override public TemplateNodeModel getParentNode() { return parentNode; }
        @Override public TemplateSequenceModel getChildNodes() { return this; }
        @Override public String getNodeName() { return key; }
        @Override public String getNodeType() { return "attribute"; }
        /** Namespace not supported for now. */
        @Override public String getNodeNamespace() { return null; }

        // TemplateSequenceModel methods
        @Override public TemplateModel get(int i) {
            if (i == 0) try {
                return wrapper.wrap(value);
            } catch (TemplateModelException e) {
                throw new BaseException("Error wrapping object for FreeMarker", e);
            }
            throw new IndexOutOfBoundsException("Attribute node only has 1 value. Tried to get index [${i}] for attribute [${key}]");
        }
        @Override public int size() { return 1; }

        // TemplateScalarModel methods
        @Override public String getAsString() { return value; }
        @Override public String toString() { return value; }
    }


    public static class FtlTextWrapper implements TemplateNodeModel, TemplateSequenceModel, AdapterTemplateModel,
            TemplateScalarModel {
        protected String text;
        FtlNodeWrapper parentNode;
        FtlTextWrapper(String text, FtlNodeWrapper parentNode) {
            this.text = text;
            this.parentNode = parentNode;
        }

        @Override public Object getAdaptedObject(Class aClass) { return text; }

        // TemplateNodeModel methods
        @Override public TemplateNodeModel getParentNode() { return parentNode; }
        @Override public TemplateSequenceModel getChildNodes() { return this; }
        @Override public String getNodeName() { return "@text"; }
        @Override public String getNodeType() { return "text"; }
        /** Namespace not supported for now. */
        @Override public String getNodeNamespace() { return null; }

        // TemplateSequenceModel methods
        @Override public TemplateModel get(int i) {
            if (i == 0) try {
                return wrapper.wrap(getAsString());
            } catch (TemplateModelException e) {
                throw new BaseException("Error wrapping object for FreeMarker", e);
            }
            throw new IndexOutOfBoundsException("Text node only has 1 value. Tried to get index [${i}]");
        }
        @Override public int size() { return 1; }

        // TemplateScalarModel methods
        @Override public String getAsString() { return text != null ? text : ""; }
        @Override public String toString() { return getAsString(); }
    }

    public static class FtlNodeListWrapper implements TemplateSequenceModel {
        ArrayList<TemplateModel> nodeList = new ArrayList<>();
        FtlNodeListWrapper(ArrayList<MNode> mnodeList, FtlNodeWrapper parentNode) {
            for (int i = 0; i < mnodeList.size(); i++)
                nodeList.add(new FtlNodeWrapper(mnodeList.get(i), parentNode));
        }
        FtlNodeListWrapper(String text, FtlNodeWrapper parentNode) {
            nodeList.add(new FtlTextWrapper(text, parentNode));
        }

        @Override public TemplateModel get(int i) { return nodeList.get(i); }
        @Override public int size() { return nodeList.size(); }
        @Override public String toString() { return nodeList.toString(); }
    }
}
