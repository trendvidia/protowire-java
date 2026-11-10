// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.

// Command go-timestamps prints protowire-go's verdict on each timestamp
// literal of a list, one line per literal:
//
//	<literal> TAB <verdict> TAB <seconds> TAB <nanos>
//
// The verdict is Parse("t = <literal>")'s: "ok", or the error. For an
// accepted literal, seconds and nanos are the instant protowire-go reads it
// as (the decoder's time.Parse, RFC3339Nano then RFC3339): Unix seconds and
// the nanosecond. TimestampGrammarTest holds Java to it, and the conformance
// job reruns it against protowire-go main. Run it from a protowire-go
// checkout:
//
//	cd ../protowire-go && go run ../protowire-java/scripts/go-timestamps/main.go \
//	    ../protowire-java/pxf-runtime/src/test/resources/timestamps/variants.txt \
//	    > ../protowire-java/pxf-runtime/src/test/resources/timestamps/go.tsv
package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/trendvidia/protowire-go/encoding/pxf"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: go-timestamps <variants.txt>")
		os.Exit(2)
	}
	f, err := os.Open(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, "go-timestamps:", err)
		os.Exit(1)
	}
	defer f.Close()
	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		lit := sc.Text()
		if lit == "" || strings.HasPrefix(lit, "#") {
			continue
		}
		verdict, sec, nsec := "ok", "", ""
		if _, err := pxf.Parse([]byte("t = " + lit)); err != nil {
			verdict = err.Error()
		} else {
			t, err := time.Parse(time.RFC3339Nano, lit)
			if err != nil {
				t, err = time.Parse(time.RFC3339, lit)
			}
			if err != nil {
				verdict = "parsed, but time.Parse refuses: " + err.Error()
			} else {
				sec, nsec = fmt.Sprint(t.Unix()), fmt.Sprint(t.Nanosecond())
			}
		}
		fmt.Fprintf(out, "%s\t%s\t%s\t%s\n", lit, verdict, sec, nsec)
	}
}
