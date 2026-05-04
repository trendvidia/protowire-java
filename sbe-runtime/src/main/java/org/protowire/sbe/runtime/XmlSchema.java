package org.protowire.sbe.runtime;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** SBE XML schema model + DOM-based parser. */
public final class XmlSchema {
    public String pkg;
    public int id;
    public int version;
    public String byteOrder;
    public final List<XmlType> types = new ArrayList<>();
    public final List<XmlComposite> composites = new ArrayList<>();
    public final List<XmlEnum> enums = new ArrayList<>();
    public final List<XmlMessage> messages = new ArrayList<>();

    public static final class XmlType {
        public String name; public String primitiveType; public int length;
    }

    public static final class XmlComposite {
        public String name;
        public final List<XmlType> types = new ArrayList<>();
        public final List<XmlRef> refs = new ArrayList<>();
    }

    public static final class XmlRef {
        public String name; public String type;
    }

    public static final class XmlEnum {
        public String name; public String encodingType;
        public final List<XmlValidValue> values = new ArrayList<>();
    }

    public static final class XmlValidValue {
        public String name; public String value;
    }

    public static final class XmlMessage {
        public String name; public int id;
        public final List<XmlField> fields = new ArrayList<>();
        public final List<XmlGroup> groups = new ArrayList<>();
    }

    public static final class XmlField { public String name; public int id; public String type; }

    public static final class XmlGroup {
        public String name; public int id;
        public final List<XmlField> fields = new ArrayList<>();
    }

    public static XmlSchema parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder().parse(new InputSource(new ByteArrayInputStream(xml)));
            Element root = doc.getDocumentElement();

            XmlSchema s = new XmlSchema();
            s.pkg = root.getAttribute("package");
            s.id = parseInt(root.getAttribute("id"), 0);
            s.version = parseInt(root.getAttribute("version"), 0);
            s.byteOrder = root.getAttribute("byteOrder");

            for (Element typesElem : children(root, "types")) {
                for (Element t : children(typesElem, "type"))      s.types.add(parseType(t));
                for (Element c : children(typesElem, "composite")) s.composites.add(parseComposite(c));
                for (Element e : children(typesElem, "enum"))      s.enums.add(parseEnum(e));
            }
            for (Element m : children(root, "message", "sbe:message")) s.messages.add(parseMessage(m));
            return s;
        } catch (Exception e) {
            throw new RuntimeException("sbe: parse XML schema: " + e.getMessage(), e);
        }
    }

    static XmlType parseType(Element e) {
        XmlType t = new XmlType();
        t.name = e.getAttribute("name");
        t.primitiveType = e.getAttribute("primitiveType");
        t.length = parseInt(e.getAttribute("length"), 0);
        return t;
    }

    static XmlComposite parseComposite(Element e) {
        XmlComposite c = new XmlComposite();
        c.name = e.getAttribute("name");
        for (Element t : children(e, "type")) c.types.add(parseType(t));
        for (Element r : children(e, "ref")) {
            XmlRef ref = new XmlRef();
            ref.name = r.getAttribute("name");
            ref.type = r.getAttribute("type");
            c.refs.add(ref);
        }
        return c;
    }

    static XmlEnum parseEnum(Element e) {
        XmlEnum en = new XmlEnum();
        en.name = e.getAttribute("name");
        en.encodingType = e.getAttribute("encodingType");
        for (Element v : children(e, "validValue")) {
            XmlValidValue vv = new XmlValidValue();
            vv.name = v.getAttribute("name");
            vv.value = v.getTextContent().trim();
            en.values.add(vv);
        }
        return en;
    }

    static XmlMessage parseMessage(Element e) {
        XmlMessage m = new XmlMessage();
        m.name = e.getAttribute("name");
        m.id = parseInt(e.getAttribute("id"), 0);
        for (Element f : children(e, "field")) m.fields.add(parseField(f));
        for (Element g : children(e, "group")) {
            XmlGroup gg = new XmlGroup();
            gg.name = g.getAttribute("name");
            gg.id = parseInt(g.getAttribute("id"), 0);
            for (Element ff : children(g, "field")) gg.fields.add(parseField(ff));
            m.groups.add(gg);
        }
        return m;
    }

    static XmlField parseField(Element e) {
        XmlField f = new XmlField();
        f.name = e.getAttribute("name");
        f.id = parseInt(e.getAttribute("id"), 0);
        f.type = e.getAttribute("type");
        return f;
    }

    static List<Element> children(Element parent, String... names) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            String tag = n.getNodeName();
            for (String want : names) if (want.equals(tag)) { out.add((Element) n); break; }
        }
        return out;
    }

    static int parseInt(String s, int dflt) {
        if (s == null || s.isEmpty()) return dflt;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return dflt; }
    }
}
