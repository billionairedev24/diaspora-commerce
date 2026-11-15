// Command media issues direct uploads (UploadThing, S3-compatible, Azure Blob) and inspects uploaded files.
package main

import (
	"github.com/billionairedev24/diaspora-commerce/go/pkg/module"
	"github.com/billionairedev24/diaspora-commerce/go/services/media"
)

func main() { module.Main(media.Name, media.New) }
