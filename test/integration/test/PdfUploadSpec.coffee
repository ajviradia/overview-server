asUser = require('../support/asUser')
shouldBehaveLikeATree = require('../support/behave/likeATree')
testMethods = require('../support/testMethods')
wd = require('wd')

#  @userBrowser.waitForWhatever().executeCallback(-> $('.invisible-file-input').css(opacity: 1)).elementByCss('.invisible-file-input').click()
Url =
  index: '/documentsets'


describe 'Pdf upload', ->
  testMethods.usingPromiseChainMethods
    openPdfUploadPage: ->
      @
        .get(Url.index)
        .waitForFunctionToReturnTrueInBrowser(-> $?.fn?.dropdown? && $.isReady)
        .elementBy(tag: 'a', contains: 'Import documents').click()
        .elementBy(tag: 'a', contains: 'Upload PDF files').click()
        .waitForElementByCss('input[type=file]')

    chooseFile: (path) ->
      fullPath = "#{__dirname}/../files/#{path}"
      @
        .executeFunction(-> $('.invisible-file-input').css(opacity: 1))
        .elementByCss('.invisible-file-input').sendKeys(fullPath)


    doImport: ->
      firstUrl = null

      isAtNewUrl = new wd.asserters.Asserter (browser, cb) ->
        browser.url (err, url) ->
          if !err && url == originalUrl
            err = "Expected URL to change, but it is still #{originalUrl}"
          url = null if err
          cb(err, url)

      @
        .url (u) -> firstUrl = url
        .elementBy(tag: 'button', contains: 'Import documents').click()
        .waitFor(isAtNewUrl, 5000)

    deleteTopUpload: ->
      @
        .get(Url.index)
        .acceptingNextAlert()
        .elementBy(tag: 'input', class: 'btn-danger', value: 'Delete').click()


        
  asUser.usingTemporaryUser()

  describe 'after uploading pdfs', ->
    before ->
      @userBrowser
        .openPdfUploadPage()
        .chooseFile('PdfUpload/Cat1.pdf')
        .chooseFile('PdfUpload/Cat2.pdf')
        .chooseFile('PdfUpload/Cat3.pdf')
        .chooseFile('PdfUpload/Cat4.pdf')
        .chooseFile('PdfUpload/Jules1.pdf')
        .chooseFile('PdfUpload/Jules2.pdf')
        .chooseFile('PdfUpload/Jules3.pdf')
        .waitForElementBy(tag: 'button', contains: 'Done adding files').click()
        .waitForElementBy(name: 'name', visible: true)
        .type('Pdf Upload\n')

    after ->
      @userBrowser
        .deleteTopUpload()
    
    it 'should show document set', ->
      @userBrowser
        .get(Url.index)
        .waitForElementBy({tag: 'h3', contains: 'Pdf Upload'}, 10000).should.eventually.exist
        
    describe 'in the default tree', ->
      before ->
        @userBrowser
          .get(Url.index)
          .waitForElementBy(tag: 'a', contains: 'Pdf Upload', visible: true).click()

          
      shouldBehaveLikeATree
        documents: [
          { type: 'pdf', title: 'Cat4.pdf' }
        ]
        searches: [
          { query: 'chase', nResults: 4 }
        ]

                                                      


  