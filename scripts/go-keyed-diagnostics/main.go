// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.

// Command go-keyed-diagnostics prints what protowire-go says of each
// document in a case list about its keyed repeated fields (draft -01 §3.13),
// one line per document:
//
//	<name> TAB <decode> TAB <diagnostics>
//
// The case list (pxf/src/test/resources/keyed-diagnostics/cases.tsv) has one
// case per line: <name> TAB <message type> TAB <document, base64>. <decode> is
// UnmarshalFull's verdict: "ok", "keyed" and the KeyedError's fields when it
// returns one, or "error" and the text. <diagnostics> is ParseTolerant then
// KeyedDiagnostics: each diagnostic's kind, position, field, key and
// message, joined by " | ", after any syntax error ParseTolerant reported.
// KeyedDiagnosticsTest holds the full tier to it, and the conformance job
// reruns it against protowire-go main. Run it from a protowire-go checkout:
//
//	cd ../protowire-go && go run ../protowire-java/scripts/go-keyed-diagnostics/main.go \
//	    ../protowire-java/pxf/src/test/resources/keyed-diagnostics/cases.tsv \
//	    ../protowire-java/testdata ../protowire-java/pxf/src/test/proto \
//	    ../protowire-java/proto-annotations/src/main/proto \
//	    > ../protowire-java/pxf/src/test/resources/keyed-diagnostics/go.tsv
package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"strings"

	"github.com/trendvidia/protocompile"
	"github.com/trendvidia/protowire-go/encoding/pxf"
	"google.golang.org/protobuf/reflect/protoreflect"
	"google.golang.org/protobuf/reflect/protoregistry"
	"google.golang.org/protobuf/types/dynamicpb"
)

func main() {
	if len(os.Args) != 5 {
		fmt.Fprintln(os.Stderr, "usage: go-keyed-diagnostics <cases.tsv> <testdata> <test proto dir> <annotations dir>")
		os.Exit(2)
	}
	c := protocompile.Compiler{Resolver: protocompile.WithStandardImports(
		&protocompile.SourceResolver{ImportPaths: []string{os.Args[2], os.Args[3], os.Args[4]}})}
	fs, err := c.Compile(context.Background(), "keyed/keyed.proto", "undo/v1/undo.proto")
	if err != nil {
		fail(err)
	}
	files := new(protoregistry.Files)
	for _, f := range fs {
		if err := files.RegisterFile(f); err != nil {
			fail(err)
		}
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
		col := strings.Split(line, "\t")
		if len(col) != 3 {
			fail(fmt.Errorf("case line has %d columns: %q", len(col), line))
		}
		doc, err := base64.StdEncoding.DecodeString(col[2])
		if err != nil {
			fail(fmt.Errorf("%s: %w", col[0], err))
		}
		d, err := files.FindDescriptorByName(protoreflect.FullName(col[1]))
		if err != nil {
			fail(fmt.Errorf("%s: type %q: %w", col[0], col[1], err))
		}
		desc := d.(protoreflect.MessageDescriptor)
		fmt.Fprintf(out, "%s\t%s\t%s\n", col[0], decode(doc, desc), diagnostics(doc, desc))
	}
	if err := sc.Err(); err != nil {
		fail(err)
	}
}

func keyed(e *pxf.KeyedError) string {
	return fmt.Sprintf("%s %d:%d:%d field=%q key=%q %s", e.Kind, e.Pos.Line, e.Pos.Column, e.Pos.Offset, e.Field, e.Key, e.Msg)
}

func decode(doc []byte, desc protoreflect.MessageDescriptor) string {
	_, err := pxf.UnmarshalFull(doc, dynamicpb.NewMessage(desc))
	if err == nil {
		return "ok"
	}
	var kerr *pxf.KeyedError
	if errors.As(err, &kerr) {
		return "keyed " + keyed(kerr)
	}
	return "error " + oneLine(err.Error())
}

func diagnostics(doc []byte, desc protoreflect.MessageDescriptor) string {
	tree, errs := pxf.ParseTolerant(doc)
	var s []string
	for _, e := range errs {
		s = append(s, "syntax "+oneLine(e.Error()))
	}
	for _, k := range pxf.KeyedDiagnostics(tree, desc) {
		s = append(s, keyed(&k))
	}
	return strings.Join(s, " | ")
}

func oneLine(s string) string {
	if strings.ContainsAny(s, "\t\n") {
		fail(fmt.Errorf("text holds a tab or newline: %q", s))
	}
	return s
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "go-keyed-diagnostics:", err)
	os.Exit(1)
}
