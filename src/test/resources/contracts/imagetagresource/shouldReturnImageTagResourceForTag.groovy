package imagetagresource

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'POST'
    url $(
        stub(~/\/manifest/),
        test('/manifest')
    )
    body(file('requests/ImageTagResource.json'))
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/ImageTagResource.json'))
  }
}