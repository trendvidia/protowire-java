// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.

// Command go-dataset-write prints what protowire-go's @dataset writer does
// with each case of pxf/src/test/resources/dataset-write/cases.tsv, one line
// per case:
//
//	<message type> TAB <columns> TAB <message, PB hex> TAB <header> TAB <row> TAB <read back>
//
// The message is the case's document read with SkipPostDecode, so a field
// the document leaves unset stays unset, and written with deterministic PB.
// <header> is AppendDatasetHeader's text and <row> AppendDatasetRow's, each
// "ERR " and the error instead when it refuses; a newline in either is
// written \n and a backslash \\. <read back> is the message a DatasetReader
// scans from the header and the row, as PB hex, when neither was refused,
// and empty otherwise. Its output is
// pxf/src/test/resources/dataset-write/go.tsv, which DatasetWriterTest holds
// the full tier to (#204). Run it from a protowire-go checkout, so its imports
// resolve through that module:
//
//	cd ../protowire-go && go run ../protowire-java/scripts/go-dataset-write/main.go \
//	    ../protowire-java/pxf/src/test/resources/dataset-write/cases.tsv \
//	    ../protowire-java/pxf/src/test/proto ../protowire-java/proto-annotations/src/main/proto \
//	    > ../protowire-java/pxf/src/test/resources/dataset-write/go.tsv
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/hex"
	"fmt"
	"os"
	"strings"

	"github.com/trendvidia/protocompile"
	"github.com/trendvidia/protowire-go/encoding/pxf"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/reflect/protoreflect"
	"google.golang.org/protobuf/reflect/protoregistry"
	"google.golang.org/protobuf/types/dynamicpb"
)

func main() {
	cases, protoDir, annotDir := os.Args[1], os.Args[2], os.Args[3]
	comp := protocompile.Compiler{Resolver: protocompile.WithStandardImports(
		&protocompile.SourceResolver{ImportPaths: []string{protoDir, annotDir}})}
	fs, err := comp.Compile(context.Background(), "dsw/v1/dsw.proto", "dsw/v1/dswnull.proto", "dsw/v1/dswl.proto")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	files := new(protoregistry.Files)
	for _, f := range fs {
		if err := files.RegisterFile(f); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
	in, err := os.Open(cases)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	defer in.Close()
	sc := bufio.NewScanner(in)
	for sc.Scan() {
		line := sc.Text()
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		f := strings.SplitN(line, "\t", 3)
		typ, cols, doc := f[0], f[1], strings.ReplaceAll(f[2], `\n`, "\n")
		var columns []string
		if cols != "" {
			columns = strings.Split(cols, ",")
		}
		d, err := files.FindDescriptorByName(protoreflect.FullName(typ))
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		md := d.(protoreflect.MessageDescriptor)
		msg := dynamicpb.NewMessage(md)
		if err := (pxf.UnmarshalOptions{SkipPostDecode: true}).Unmarshal([]byte(doc), msg); err != nil {
			fmt.Fprintf(os.Stderr, "%s %q: %v\n", typ, doc, err)
			os.Exit(1)
		}
		pb, err := proto.MarshalOptions{Deterministic: true}.Marshal(msg)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		header, herr := pxf.AppendDatasetHeader(nil, md, columns)
		row, rerr := pxf.AppendDatasetRow(nil, msg, columns)
		back := ""
		if herr == nil && rerr == nil {
			back = readBack(md, append(header, row...))
		}
		fmt.Printf("%s\t%s\t%s\t%s\t%s\t%s\n", typ, cols, hex.EncodeToString(pb), text(header, herr), text(row, rerr), back)
	}
}

// readBack scans the one row of table into a message of type md, and returns
// it as deterministic PB hex, or "ERR " and the error.
func readBack(md protoreflect.MessageDescriptor, table []byte) string {
	tr, err := pxf.NewDatasetReader(bytes.NewReader(table))
	if err != nil {
		return "ERR " + err.Error()
	}
	m := dynamicpb.NewMessage(md)
	if err := tr.Scan(m); err != nil {
		return "ERR " + err.Error()
	}
	pb, err := proto.MarshalOptions{Deterministic: true}.Marshal(m)
	if err != nil {
		return "ERR " + err.Error()
	}
	return hex.EncodeToString(pb)
}

func text(b []byte, err error) string {
	s := string(b)
	if err != nil {
		s = "ERR " + err.Error()
	}
	return strings.ReplaceAll(strings.ReplaceAll(s, `\`, `\\`), "\n", `\n`)
}
