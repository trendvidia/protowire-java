// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.

// Command go-tolerant prints what protowire-go's ParseTolerant makes of each
// document in a case list, one line per document:
//
//	<name> TAB <document, base64> TAB <errors> TAB <tree>
//
// The case list (pxf-runtime/src/test/resources/tolerant/cases.tsv) has one
// case per line: <name> TAB <whole|prefixes> TAB <document, base64>. A
// "prefixes" case stands for every prefix of its document, named <name>#<n>
// for the prefix of n bytes: the states an editor buffer passes through while
// the document is typed. <errors> is every error, as line:col:offset and the
// message, joined by " | ". <tree> is the document in a fixed notation that
// pxf-runtime's TolerantParseTest writes for Java's tree, so the two compare
// as text. It covers what both trees hold; protowire-go's End positions and
// BodyOffset, which Java's tree has no counterpart of, are left out. Its output
// is pxf-runtime/src/test/resources/tolerant/go.tsv, and the conformance job
// reruns it against protowire-go main. Run it from a protowire-go checkout:
//
//	cd ../protowire-go && go run ../protowire-java/scripts/go-tolerant/main.go \
//	    ../protowire-java/pxf-runtime/src/test/resources/tolerant/cases.tsv \
//	    > ../protowire-java/pxf-runtime/src/test/resources/tolerant/go.tsv
package main

import (
	"bufio"
	"encoding/base64"
	"fmt"
	"os"
	"strings"
	"unicode/utf8"

	"github.com/trendvidia/protowire-go/encoding/pxf"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: go-tolerant <cases.tsv>")
		os.Exit(2)
	}
	f, err := os.Open(os.Args[1])
	if err != nil {
		fail(err)
	}
	defer f.Close()
	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		line := sc.Text()
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		c := strings.Split(line, "\t")
		if len(c) != 3 {
			fail(fmt.Errorf("case line has %d columns: %q", len(c), line))
		}
		doc, err := base64.StdEncoding.DecodeString(c[2])
		if err != nil {
			fail(fmt.Errorf("%s: %w", c[0], err))
		}
		switch c[1] {
		case "whole":
			row(out, c[0], doc)
		case "prefixes":
			for n := 0; n <= len(doc); n++ {
				row(out, fmt.Sprintf("%s#%d", c[0], n), doc[:n])
			}
		default:
			fail(fmt.Errorf("%s: mode %q", c[0], c[1]))
		}
	}
	if err := sc.Err(); err != nil {
		fail(err)
	}
}

func row(out *bufio.Writer, name string, doc []byte) {
	tree, errs := pxf.ParseTolerant(doc)
	es := make([]string, len(errs))
	for i, e := range errs {
		es[i] = pos(e.Pos) + " " + q(e.Msg)
	}
	fmt.Fprintf(out, "%s\t%s\t%s\t%s\n", name, base64.StdEncoding.EncodeToString(doc), strings.Join(es, " | "), document(tree))
}

func pos(p pxf.Position) string { return fmt.Sprintf("%d:%d:%d", p.Line, p.Column, p.Offset) }

// q quotes s: backslash, quote and control bytes escaped, a non-ASCII rune
// as \u{hex}, a byte that is not UTF-8 as \x{hex}.
func q(s string) string {
	var b strings.Builder
	b.WriteByte('"')
	for i := 0; i < len(s); {
		r, n := utf8.DecodeRuneInString(s[i:])
		switch {
		case r == utf8.RuneError && n <= 1:
			fmt.Fprintf(&b, `\x{%x}`, s[i])
		case r == '\\' || r == '"':
			b.WriteByte('\\')
			b.WriteRune(r)
		case r == '\n':
			b.WriteString(`\n`)
		case r == '\t':
			b.WriteString(`\t`)
		case r < 0x20 || r == 0x7f || r > 0x7e:
			fmt.Fprintf(&b, `\u{%x}`, r)
		default:
			b.WriteRune(r)
		}
		i += n
	}
	b.WriteByte('"')
	return b.String()
}

func comments(cs []pxf.Comment) string {
	s := make([]string, len(cs))
	for i, c := range cs {
		s[i] = pos(c.Pos) + q(c.Text)
	}
	return "[" + strings.Join(s, ",") + "]"
}

func strs(ss []string) string {
	s := make([]string, len(ss))
	for i, x := range ss {
		s[i] = q(x)
	}
	return "[" + strings.Join(s, ",") + "]"
}

func body(b []byte) string {
	if b == nil {
		return "-"
	}
	return q(string(b))
}

func document(d *pxf.Document) string {
	var b strings.Builder
	fmt.Fprintf(&b, "doc type=%s lead=%s", q(d.TypeURL), comments(d.LeadingComments))
	for _, x := range d.Directives {
		fmt.Fprintf(&b, " dir(%s @%s prefixes=%s type=%s body=%s lead=%s)", pos(x.Pos), q(x.Name), strs(x.Prefixes), q(x.Type), body(x.Body), comments(x.LeadingComments))
	}
	for _, x := range d.Datasets {
		fmt.Fprintf(&b, " dataset(%s type=%s cols=%s lead=%s rows=[", pos(x.Pos), q(x.Type), strs(x.Columns), comments(x.LeadingComments))
		for i, r := range x.Rows {
			if i > 0 {
				b.WriteByte(',')
			}
			fmt.Fprintf(&b, "row(%s lead=%s cells=[", pos(r.Pos), comments(r.LeadingComments))
			for j, c := range r.Cells {
				if j > 0 {
					b.WriteByte(',')
				}
				if c == nil {
					b.WriteString("_")
				} else {
					value(&b, c)
				}
			}
			b.WriteString("])")
		}
		b.WriteString("])")
	}
	for _, x := range d.Protos {
		fmt.Fprintf(&b, " proto(%s shape=%d name=%s body=%s lead=%s)", pos(x.Pos), x.Shape, q(x.TypeName), body(x.Body), comments(x.LeadingComments))
	}
	b.WriteString(" entries=")
	entries(&b, d.Entries)
	fmt.Fprintf(&b, " trail=%s", comments(d.TrailingComments))
	return b.String()
}

func entries(b *strings.Builder, es []pxf.Entry) {
	b.WriteByte('[')
	for i, e := range es {
		if i > 0 {
			b.WriteByte(',')
		}
		switch e := e.(type) {
		case *pxf.Assignment:
			fmt.Fprintf(b, "A(%s %s quoted=%t lead=%s tc=%s = ", pos(e.Pos), q(e.Key), e.KeyQuoted, comments(e.LeadingComments), q(e.TrailingComment))
			value(b, e.Value)
			b.WriteByte(')')
		case *pxf.MapEntry:
			fmt.Fprintf(b, "M(%s %s quoted=%t lead=%s tc=%s : ", pos(e.Pos), q(e.Key), e.KeyQuoted, comments(e.LeadingComments), q(e.TrailingComment))
			value(b, e.Value)
			b.WriteByte(')')
		case *pxf.Block:
			fmt.Fprintf(b, "B(%s %s quoted=%t lead=%s ", pos(e.Pos), q(e.Name), e.NameQuoted, comments(e.LeadingComments))
			entries(b, e.Entries)
			fmt.Fprintf(b, " trail=%s)", comments(e.TrailingComments))
		case *pxf.TypeEntry:
			fmt.Fprintf(b, "T(%s %s lead=%s tc=%s)", pos(e.Pos), q(e.URL), comments(e.LeadingComments), q(e.TrailingComment))
		default:
			fail(fmt.Errorf("entry %T", e))
		}
	}
	b.WriteByte(']')
}

func value(b *strings.Builder, v pxf.Value) {
	switch v := v.(type) {
	case *pxf.StringVal:
		fmt.Fprintf(b, "S(%s %s)", pos(v.Pos), q(v.Value))
	case *pxf.IntVal:
		fmt.Fprintf(b, "I(%s %s p=%s)", pos(v.Pos), q(v.Raw), q(v.Prec))
	case *pxf.FloatVal:
		fmt.Fprintf(b, "F(%s %s p=%s)", pos(v.Pos), q(v.Raw), q(v.Prec))
	case *pxf.BoolVal:
		fmt.Fprintf(b, "Bool(%s %t)", pos(v.Pos), v.Value)
	case *pxf.BytesVal:
		fmt.Fprintf(b, "Bytes(%s %s)", pos(v.Pos), base64.StdEncoding.EncodeToString(v.Value))
	case *pxf.TimestampVal:
		fmt.Fprintf(b, "Ts(%s %s)", pos(v.Pos), q(v.Raw))
	case *pxf.DurationVal:
		fmt.Fprintf(b, "Dur(%s %s)", pos(v.Pos), q(v.Raw))
	case *pxf.NullVal:
		fmt.Fprintf(b, "Null(%s)", pos(v.Pos))
	case *pxf.IdentVal:
		fmt.Fprintf(b, "Id(%s %s)", pos(v.Pos), q(v.Name))
	case *pxf.ListVal:
		fmt.Fprintf(b, "List(%s [", pos(v.Pos))
		for i, e := range v.Elements {
			if i > 0 {
				b.WriteByte(',')
			}
			value(b, e)
		}
		b.WriteString("])")
	case *pxf.BlockVal:
		fmt.Fprintf(b, "Blk(%s ", pos(v.Pos))
		entries(b, v.Entries)
		fmt.Fprintf(b, " trail=%s)", comments(v.TrailingComments))
	case *pxf.BadVal:
		fmt.Fprintf(b, "Bad(%s)", pos(v.Pos))
	default:
		fail(fmt.Errorf("value %T", v))
	}
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "go-tolerant:", err)
	os.Exit(1)
}
